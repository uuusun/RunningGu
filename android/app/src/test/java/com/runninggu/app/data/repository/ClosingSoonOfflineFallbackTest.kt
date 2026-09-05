package com.runninggu.app.data.repository

import com.runninggu.app.data.local.cache.ClosingSoonCache
import com.runninggu.app.data.local.cache.ClosingSoonSnapshot
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.dto.ClosingSoonDto
import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.data.remote.dto.ContestListDto
import com.runninggu.app.data.remote.dto.DailyCountsDto
import com.runninggu.app.data.remote.dto.NearbyFestivalListDto
import com.runninggu.app.domain.today
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * 오프라인에서 **홈 마감임박**이 되살아나는가. (SPEC §6.1 · 매핑표 S1 오프라인 · 이슈 #276)
 *
 * #275 가 목록·상세에 폴백을 붙였는데 마감임박은 `GET /contests/closing-soon` 이라 안 닿았다.
 * 캘린더는 캐시 20건이 살아나는데 **홈만 그대로 빈 화면**이었다.
 *
 * ## 목록·상세 폴백과 결정적으로 다른 것
 *
 * `dDayApply` 는 **시간이 지나면 거짓이 된다.**
 *
 * ```
 * 대회 날짜 10.03  →  사흘 뒤에 꺼내도 10.03      안 낡는다
 * 마감 D-4        →  사흘 뒤에 꺼내면 여전히 D-4   낡는다
 * ```
 *
 * 이미 마감된 대회를 "마감 D-2" 로 보여주면 사용자가 신청하러 갔다가 헛걸음한다.
 * **안 보여주는 것보다 나쁘다.** 그래서 꺼낼 때마다 오늘로 다시 세고, 끝난 것은 뺀다.
 *
 * # 망가뜨리면 이것만 실패한다
 * ```
 * ① 저장된 dDayApply 를 그대로 쓴다  →  1개 실패
 *      마감까지 남은 날을 오늘 기준으로 다시 센다 FAILED
 *
 * ② 접수 종료 필터를 뺀다  →  2개 실패
 *      접수가 끝난 대회는 빼고 되살린다 FAILED
 *      다 빼서 0건이면 오류가 아니라 빈 목록이다 FAILED
 *
 * ③ snapshot 이 null 일 때 빈 결과를 돌려준다  →  1개 실패
 *      되살릴 snapshot 이 없으면 원래 오류를 그대로 던진다 FAILED
 *
 * ④ catch 를 ApiException 으로 넓힌다  →  1개 실패
 *      서버가 답을 준 실패에는 캐시를 쓰지 않는다 FAILED
 *
 * ⑤ cachedAt 을 안 올린다  →  1개 실패
 *      되살린 결과에는 언제 것인지가 붙는다 FAILED
 * ```
 */
class ClosingSoonOfflineFallbackTest {

    // ── 가짜들 ────────────────────────────────────────────────

    private class StubApi(
        val failure: Throwable? = null,
        val items: List<ContestDto> = emptyList(),
    ) : ContestApi {
        var calls = 0

        override suspend fun closingSoon(limit: Int): ClosingSoonDto {
            calls++
            failure?.let { throw it }
            return ClosingSoonDto(items = items)
        }

        override suspend fun list(
            query: String?,
            events: List<String>?,
            openOnly: Boolean?,
            regions: List<String>?,
            date: String?,
            cursor: String?,
            size: Int?,
        ): ContestListDto = ContestListDto()

        override suspend fun detail(id: Long): ContestDto = items.first { it.id == id }

        override suspend fun dailyCounts(
            year: Int,
            month: Int,
            query: String?,
            events: List<String>?,
            openOnly: Boolean?,
            regions: List<String>?,
        ): DailyCountsDto = DailyCountsDto()

        override suspend fun festivals(id: Long): NearbyFestivalListDto = NearbyFestivalListDto()
    }

    /** 인메모리 snapshot. 만료 판정은 [RoomClosingSoonCacheTest] 가 따로 본다. */
    private class FakeCache(var stored: ClosingSoonSnapshot? = null) : ClosingSoonCache {
        override suspend fun save(contests: List<ContestDto>) {
            // 빈 응답도 담는다 — 원자 교체 계약에 예외가 없다 (#283 리뷰)
            stored = ClosingSoonSnapshot(contests, SAVED_AT)
        }

        override suspend fun snapshot(): ClosingSoonSnapshot? = stored

        override suspend fun clear() {
            stored = null
        }
    }

    private fun contest(id: Long, applyEnd: LocalDate?, dDayApply: Int? = null) = ContestDto(
        id = id,
        name = "대회$id",
        contestDate = LocalDate.of(2026, 12, 1),
        applyEnd = applyEnd,
        dDayApply = dDayApply,
    )

    private fun snapshot(vararg contests: ContestDto) =
        FakeCache(ClosingSoonSnapshot(contests.toList(), SAVED_AT))

    private val offline = IOException("offline")

    // ── 되살아나는 자리 ────────────────────────────────────────

    @Test
    fun `연결이 끊기면 서버가 골라 준 순서 그대로 되살린다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            closingSoonCache = snapshot(
                contest(30, today().plusDays(1)),
                contest(10, today().plusDays(2)),
                contest(20, today().plusDays(3)),
            ),
        )

        // **앱이 다시 고르지도 정렬하지도 않는다.** 고르는 규칙은 서버 것이라 앱이 구현하면
        // 서버가 규칙을 바꿨을 때 온라인과 오프라인이 다른 목록을 보여준다
        assertEquals(
            listOf("30", "10", "20"),
            repository.closingSoon(4).items.map { it.contest.id },
        )
    }

    @Test
    fun `마감까지 남은 날을 오늘 기준으로 다시 센다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            // 저장 당시 서버가 "D-4" 라고 했지만 그 사이 사흘이 지나 오늘 기준으로는 D-1 이다
            closingSoonCache = snapshot(contest(1, applyEnd = today().plusDays(1), dDayApply = 4)),
        )

        assertEquals(1, repository.closingSoon(4).items.single().dDayApply)
    }

    @Test
    fun `접수가 끝난 대회는 빼고 되살린다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            closingSoonCache = snapshot(
                contest(1, applyEnd = today().minusDays(1)),  // 어제 끝났다
                contest(2, applyEnd = today()),               // 오늘까지다 — 아직 살아 있다
            ),
        )

        assertEquals(listOf("2"), repository.closingSoon(4).items.map { it.contest.id })
    }

    @Test
    fun `마감일이 없으면 끝났는지 알 수 없으므로 남긴다`() = runBlocking {
        // 서버가 마감임박으로 골라 준 항목이다. "모르니까 버린다" 보다 남기는 쪽이 맞고,
        // dDayApply 가 null 이라 화면은 배지를 숨긴다
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            closingSoonCache = snapshot(contest(1, applyEnd = null)),
        )

        val item = repository.closingSoon(4).items.single()

        assertEquals("1", item.contest.id)
        assertNull(item.dDayApply)
    }

    @Test
    fun `다 빼서 0건이면 오류가 아니라 빈 목록이다`() = runBlocking {
        // **되살릴 snapshot 이 없는 것과 다르다.** 이건 "되살렸는데 보여줄 게 없다" 라
        // 정상 빈 상태고, 화면은 [다시 시도] 가 아니라 Empty 를 그린다
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            closingSoonCache = snapshot(contest(1, applyEnd = today().minusDays(3))),
        )

        assertEquals(emptyList<ClosingSoon>(), repository.closingSoon(4).items)
    }

    @Test
    fun `되살린 결과에는 언제 것인지가 붙는다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            closingSoonCache = snapshot(contest(1, applyEnd = today().plusDays(1))),
        )

        assertEquals(Instant.ofEpochMilli(SAVED_AT), repository.closingSoon(4).cachedAt)
    }

    @Test
    fun `서버에서 막 받은 것에는 붙지 않는다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(items = listOf(contest(1, applyEnd = today().plusDays(1), dDayApply = 1))),
            closingSoonCache = FakeCache(),
        )

        // 서버 응답에 cachedAt 을 달면 화면이 멀쩡한 목록에도 "마지막 성공본" 이라고 적는다
        assertNull(repository.closingSoon(4).cachedAt)
    }

    @Test
    fun `성공하면 다음 오프라인을 위해 저장한다`() = runBlocking {
        val cache = FakeCache()
        val repository = RemoteContestRepository(
            api = StubApi(items = listOf(contest(1, applyEnd = today().plusDays(1)))),
            closingSoonCache = cache,
        )

        repository.closingSoon(4)

        assertEquals(listOf(1L), cache.stored?.contests?.map { it.id })
    }

    // ── 되살아나면 안 되는 자리 ─────────────────────────────────

    @Test
    fun `되살릴 snapshot 이 없으면 원래 오류를 그대로 던진다`() = runBlocking {
        // 빈 목록으로 바꾸면 "마감임박 대회가 없다" 가 되어 사실과 다르다. 24시간이 지나
        // 만료된 경우도 여기로 온다 — snapshot() 이 null 을 준다
        val repository = RemoteContestRepository(
            api = StubApi(failure = offline),
            closingSoonCache = FakeCache(),
        )

        val thrown = runCatching { repository.closingSoon(4) }.exceptionOrNull()

        assertTrue("네트워크 오류가 그대로 와야 한다: $thrown", thrown is ApiException.Network)
    }

    @Test
    fun `서버가 답을 준 실패에는 캐시를 쓰지 않는다`() = runBlocking {
        // 503 은 **연결이 살아 있다**는 뜻이다
        val repository = RemoteContestRepository(
            api = StubApi(failure = ApiException.Http(503, ApiErrorCode.INTERNAL_SERVER_ERROR, null)),
            closingSoonCache = snapshot(contest(1, applyEnd = today().plusDays(1))),
        )

        val thrown = runCatching { repository.closingSoon(4) }.exceptionOrNull()

        assertTrue("캐시로 덮으면 안 된다: $thrown", thrown is ApiException.Http)
    }

    @Test
    fun `캐시가 없어도 서버만으로 돈다`() = runBlocking {
        // ServiceLocator.bind 를 안 부른 상태(단위 테스트)가 이것이다
        val api = StubApi(items = listOf(contest(1, applyEnd = today().plusDays(1))))
        val repository = RemoteContestRepository(api)

        assertEquals(1, repository.closingSoon(4).items.size)
        assertEquals(1, api.calls)
    }

    private companion object {
        /** 앱이 snapshot 을 저장한 시각. 값 자체에는 뜻이 없고 그대로 올라오는지만 본다. */
        const val SAVED_AT = 1_700_000_000_000L
    }
}

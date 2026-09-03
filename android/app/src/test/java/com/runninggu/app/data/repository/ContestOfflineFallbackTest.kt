package com.runninggu.app.data.repository

import com.runninggu.app.data.local.cache.ContestCache
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.dto.ClosingSoonDto
import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.data.remote.dto.ContestListDto
import com.runninggu.app.data.remote.dto.DailyCountsDto
import com.runninggu.app.data.remote.dto.NearbyFestivalListDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * 오프라인에서 **마지막으로 본 대회**가 되살아나는가. (SPEC §6.1 · §9.3 · 이슈 #105)
 *
 * 그전에는 연결이 끊기면 홈·캘린더·상세가 전부 오류였다. 세션은 복원되는데(#89) 볼 것이
 * 없었다 — 공모전 시연 장소의 네트워크가 불안하면 그게 그대로 보인다.
 *
 * ## 이 파일이 지키는 것은 "되살아난다" 가 아니라 **"아무 때나 되살아나지 않는다"** 다
 *
 * 폴백은 넓힐수록 위험해진다. 서버가 답을 준 것(`4xx`·`5xx`)은 **연결이 살아 있다는
 * 뜻**이라, 그때 낡은 목록을 그리면 사용자는 지금 서버가 말한 것과 다른 화면을 보면서도
 * 무엇이 최신인지 알 방법이 없다.
 *
 * # 망가뜨리면 이것만 실패한다
 * ```
 * ① catch (e: ApiException.Network) 를 catch (e: ApiException) 으로 넓힌다  →  1개 실패
 *      서버가 답을 준 실패에는 캐시를 쓰지 않는다 FAILED
 *
 * ② 캐시가 비었을 때 빈 목록을 돌려주게 한다  →  1개 실패
 *      캐시가 비면 원래 오류를 그대로 던진다 FAILED
 *
 * ③ cursor != null 가드를 뺀다  →  1개 실패
 *      다음 장을 캐시로 채우지 않는다 FAILED
 * ```
 */
class ContestOfflineFallbackTest {

    // ── 가짜들 ────────────────────────────────────────────────

    /** 목록·상세만 쓴다. 나머지는 이 파일이 보는 자리가 아니다. */
    private class StubApi(
        val failure: Throwable? = null,
        val items: List<ContestDto> = emptyList(),
    ) : ContestApi {
        var listCalls = 0

        override suspend fun list(
            query: String?,
            events: List<String>?,
            openOnly: Boolean?,
            regions: List<String>?,
            date: String?,
            cursor: String?,
            size: Int?,
        ): ContestListDto {
            listCalls++
            failure?.let { throw it }
            return ContestListDto(items = items, nextCursor = "c2", hasNext = true)
        }

        override suspend fun detail(id: Long): ContestDto {
            failure?.let { throw it }
            return items.first { it.id == id }
        }

        override suspend fun dailyCounts(
            year: Int,
            month: Int,
            query: String?,
            events: List<String>?,
            openOnly: Boolean?,
            regions: List<String>?,
        ): DailyCountsDto = DailyCountsDto()

        override suspend fun closingSoon(limit: Int): ClosingSoonDto = ClosingSoonDto()

        override suspend fun festivals(id: Long): NearbyFestivalListDto = NearbyFestivalListDto()
    }

    /** 인메모리 캐시. Room 없이 저장소 쪽 판단만 본다. */
    private class FakeCache(initial: List<ContestDto> = emptyList()) : ContestCache {
        val saved = mutableMapOf<Long, ContestDto>()

        init {
            initial.forEach { saved[it.id] = it }
        }

        override suspend fun save(contests: List<ContestDto>) {
            contests.forEach { saved[it.id] = it }
        }

        override suspend fun list(limit: Int): List<ContestDto> =
            saved.values.sortedWith(compareBy({ it.contestDate }, { it.id })).take(limit)

        override suspend fun byId(id: Long): ContestDto? = saved[id]

        override suspend fun clear() = saved.clear()
    }

    private fun contest(id: Long, date: LocalDate = LocalDate.of(2026, 10, 1)) =
        ContestDto(id = id, name = "대회$id", contestDate = date)

    private val offline = ApiException.Network(IOException("offline"))

    // ── 되살아나는 자리 ────────────────────────────────────────

    @Test
    fun `연결이 끊기면 마지막으로 본 목록을 되살린다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(failure = IOException("offline")),
            cache = FakeCache(listOf(contest(2), contest(1))),
        )

        val page = repository.list(ContestFilter())

        assertEquals(listOf("1", "2"), page.contests.map { it.id })
        // **더 볼 것이 없다.** 커서를 지어내면 [더 보기] 가 헛돈다
        assertNull(page.nextCursor)
        assertFalse(page.hasNext)
    }

    @Test
    fun `상세도 목록에서 본 대회면 되살아난다`() = runBlocking {
        val repository = RemoteContestRepository(
            api = StubApi(failure = IOException("offline")),
            cache = FakeCache(listOf(contest(7))),
        )

        assertEquals("7", repository.detail(7).id)
    }

    @Test
    fun `성공하면 다음 오프라인을 위해 저장한다`() = runBlocking {
        val cache = FakeCache()
        val repository = RemoteContestRepository(StubApi(items = listOf(contest(1), contest(2))), cache)

        repository.list(ContestFilter())

        assertEquals(setOf(1L, 2L), cache.saved.keys)
    }

    // ── 되살아나면 안 되는 자리 ─────────────────────────────────

    @Test
    fun `서버가 답을 준 실패에는 캐시를 쓰지 않는다`() = runBlocking {
        // 503 은 **연결이 살아 있다**는 뜻이다. 낡은 목록을 대신 그리면 사용자는 지금
        // 서버가 말한 것과 다른 화면을 보면서 무엇이 최신인지 알 수 없다
        val repository = RemoteContestRepository(
            api = StubApi(failure = ApiException.Http(503, ApiErrorCode.INTERNAL_SERVER_ERROR, null)),
            cache = FakeCache(listOf(contest(1))),
        )

        val thrown = runCatching { repository.list(ContestFilter()) }.exceptionOrNull()

        assertTrue("캐시로 덮으면 안 된다: $thrown", thrown is ApiException.Http)
    }

    @Test
    fun `캐시가 비면 원래 오류를 그대로 던진다`() = runBlocking {
        // 빈 목록으로 바꾸면 "대회가 없다" 가 되어 사실과 다르다
        val repository = RemoteContestRepository(StubApi(failure = IOException("offline")), FakeCache())

        val thrown = runCatching { repository.list(ContestFilter()) }.exceptionOrNull()

        assertTrue("네트워크 오류가 그대로 와야 한다: $thrown", thrown is ApiException.Network)
    }

    @Test
    fun `다음 장을 캐시로 채우지 않는다`() = runBlocking {
        // 커서는 서버 것이라 캐시가 어디에 이어 붙어야 할지 모른다. 채우면 이미 본
        // 대회가 목록 아래에 다시 붙는다
        val repository = RemoteContestRepository(
            api = StubApi(failure = IOException("offline")),
            cache = FakeCache(listOf(contest(1))),
        )

        val thrown = runCatching { repository.list(ContestFilter(), cursor = "c2") }.exceptionOrNull()

        assertTrue("다음 장은 폴백하지 않는다: $thrown", thrown is ApiException.Network)
    }

    @Test
    fun `캐시가 없어도 서버만으로 돈다`() = runBlocking {
        // ServiceLocator.bind 를 안 부른 상태(단위 테스트)가 이것이다
        val api = StubApi(items = listOf(contest(1)))
        val repository = RemoteContestRepository(api)

        assertEquals(1, repository.list(ContestFilter()).contests.size)
        assertEquals(1, api.listCalls)
    }
}

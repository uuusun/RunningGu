package com.runninggu.app.data.local.cache

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.ContestDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

/**
 * `cached_closing_soon` snapshot 의 저장·만료 규칙. (SPEC §6.1 · 이슈 #276)
 *
 * ## 이 파일이 지키는 것
 *
 * 마감임박 캐시는 **개별 대회가 아니라 "서버가 고른 결과와 그 순서"** 를 담는다. 그래서
 * 다른 캐시와 규칙이 둘 다르다 — 통째로 바뀌고, 하루가 지나면 버린다.
 *
 * # 망가뜨리면 이것만 실패한다
 * ```
 * ① replaceAll 을 upsert 로 바꾼다  →  1개 실패
 *      지난 응답이 남지 않는다 FAILED
 *
 * ② MAX_AGE 검사를 뺀다  →  1개 실패
 *      24시간이 지난 snapshot 은 없는 것으로 친다 FAILED
 *
 * ③ 빈 응답도 저장하게 한다  →  1개 실패
 *      빈 응답은 직전 snapshot 을 지우지 않는다 FAILED
 *
 * ④ rank 대신 contestId 로 정렬한다  →  1개 실패
 *      서버가 준 순서를 그대로 되돌려준다 FAILED
 * ```
 */
class RoomClosingSoonCacheTest {

    /** 인메모리 DAO. Room 없이 [RoomClosingSoonCache] 의 판단만 본다. */
    private class FakeDao : ClosingSoonCacheDao {
        val rows = mutableListOf<ClosingSoonCacheEntity>()

        override suspend fun insert(entries: List<ClosingSoonCacheEntity>) {
            rows += entries
        }

        override suspend fun all(): List<ClosingSoonCacheEntity> = rows.sortedBy { it.rank }

        override suspend fun clear() {
            rows.clear()
        }
    }

    private fun contest(id: Long, applyEnd: LocalDate? = null) = ContestDto(
        id = id,
        name = "대회$id",
        contestDate = LocalDate.of(2026, 10, 1),
        applyEnd = applyEnd,
        // 서버가 준 값. **저장되면 안 되는 값이라** 일부러 넣어 둔다
        dDayApply = 4,
    )

    /** 시각을 손으로 돌린다. */
    private class Clock(var millis: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = millis
    }

    // ── 담는 것 ──────────────────────────────────────────────

    @Test
    fun `서버가 준 순서를 그대로 되돌려준다`() = runBlocking {
        val cache = RoomClosingSoonCache(FakeDao(), Clock())

        // id 순서와 일부러 어긋나게 준다 — id 로 정렬하면 여기서 걸린다
        cache.save(listOf(contest(30), contest(10), contest(20)))

        assertEquals(listOf(30L, 10L, 20L), cache.snapshot()?.contests?.map { it.id })
    }

    @Test
    fun `지난 응답이 남지 않는다`() = runBlocking {
        val cache = RoomClosingSoonCache(FakeDao(), Clock())
        cache.save(listOf(contest(1), contest(2), contest(3), contest(4)))

        // 서버가 이번엔 둘만 줬다 — 지난 응답의 3·4 가 남으면 서버가 주지 않은 항목이 섞인다
        cache.save(listOf(contest(9), contest(8)))

        assertEquals(listOf(9L, 8L), cache.snapshot()?.contests?.map { it.id })
    }

    @Test
    fun `빈 응답은 직전 snapshot 을 지우지 않는다`() = runBlocking {
        val cache = RoomClosingSoonCache(FakeDao(), Clock())
        cache.save(listOf(contest(1)))

        // 서버가 정상적으로 0건을 줄 수 있다. 그걸 담아 두면 오프라인에서 "마감임박이 없다"
        // 를 마지막 성공본으로 보여주게 되는데, 되살릴 값어치가 없으면서 쓸 만한 것만 잃는다
        cache.save(emptyList())

        assertEquals(listOf(1L), cache.snapshot()?.contests?.map { it.id })
    }

    // ── 만료 ────────────────────────────────────────────────

    @Test
    fun `24시간이 지난 snapshot 은 없는 것으로 친다`() = runBlocking {
        val clock = Clock()
        val cache = RoomClosingSoonCache(FakeDao(), clock)
        cache.save(listOf(contest(1)))

        clock.millis += ClosingSoonCache.MAX_AGE.toMillis()

        // **빈 목록이 아니라 null 이다.** 빈 목록을 주면 호출부가 "되살렸는데 볼 게 없다" 로
        // 읽어서 오프라인 오류가 "대회가 없음" 으로 둔갑한다
        assertNull(cache.snapshot())
    }

    @Test
    fun `24시간 직전까지는 살아 있다`() = runBlocking {
        val clock = Clock()
        val cache = RoomClosingSoonCache(FakeDao(), clock)
        cache.save(listOf(contest(1)))

        clock.millis += ClosingSoonCache.MAX_AGE.toMillis() - 1

        assertNotNull(cache.snapshot())
    }

    @Test
    fun `저장 시각을 함께 준다`() = runBlocking {
        val clock = Clock(millis = 12_345L)
        val cache = RoomClosingSoonCache(FakeDao(), clock)

        cache.save(listOf(contest(1)))
        clock.millis += Duration.ofHours(1).toMillis()

        // 꺼낸 시각이 아니라 **저장한 시각**이다 — 화면이 "언제 것" 이라고 말할 값이다
        assertEquals(12_345L, cache.snapshot()?.cachedAt)
    }

    // ── 못 읽는 행 ───────────────────────────────────────────

    @Test
    fun `못 읽는 행은 버리고 나머지를 살린다`() = runBlocking {
        val dao = FakeDao()
        val cache = RoomClosingSoonCache(dao, Clock())
        cache.save(listOf(contest(1), contest(2)))

        // 앱을 올리면서 DTO 가 바뀌면 옛 payload 가 남아 있을 수 있다
        dao.rows[0] = dao.rows[0].copy(payload = "{ 이건 JSON 이 아니다")

        assertEquals(listOf(2L), cache.snapshot()?.contests?.map { it.id })
    }

    @Test
    fun `전부 못 읽으면 되살릴 것이 없는 것과 같다`() = runBlocking {
        val dao = FakeDao()
        val cache = RoomClosingSoonCache(dao, Clock())
        cache.save(listOf(contest(1)))
        dao.rows[0] = dao.rows[0].copy(payload = "깨짐")

        assertNull(cache.snapshot())
    }

    @Test
    fun `payload 는 네트워크와 같은 규칙으로 직렬화한다`() = runBlocking {
        val dao = FakeDao()
        val cache = RoomClosingSoonCache(dao, Clock())
        val dto = contest(1, applyEnd = LocalDate.of(2026, 9, 30))

        cache.save(listOf(dto))

        // 매퍼가 한 벌이라 캐시에서 온 것과 서버에서 온 것이 갈릴 수 없다는 것이 전제다
        assertEquals(dto, ApiJson.decodeFromString(ContestDto.serializer(), dao.rows[0].payload))
    }
}

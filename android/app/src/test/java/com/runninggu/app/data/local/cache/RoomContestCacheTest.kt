package com.runninggu.app.data.local.cache

import com.runninggu.app.data.remote.dto.ContestDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 캐시 한 행을 만들고 되읽는 규칙. (SPEC §6.1 · 이슈 #105)
 *
 * **Room 없이 돈다** — `ContestCacheDao` 가 인터페이스라 가짜를 끼운다. SQL 정렬·`LIMIT`
 * 은 Room 이 하는 일이라 여기서 안 보고, **앱이 판단하는 것만** 본다.
 *
 * # 망가뜨리면 이것만 실패한다
 * ```
 * ① decode() 의 runCatching 을 벗겨 예외가 나가게 한다  →  1개 실패
 *      못 읽는 행은 버리고 나머지를 준다 FAILED
 *
 * ② contestDate 를 payload 안에만 두고 컬럼에서 뺀다  →  1개 실패
 *      정렬용 날짜를 컬럼으로 꺼내 둔다 FAILED
 * ```
 */
class RoomContestCacheTest {

    /** 넣은 순서를 그대로 돌려준다. 정렬은 Room 몫이라 흉내 내지 않는다. */
    private class FakeDao : ContestCacheDao {
        val rows = linkedMapOf<Long, ContestCacheEntity>()

        override suspend fun upsert(entries: List<ContestCacheEntity>) {
            entries.forEach { rows[it.id] = it }
        }

        override suspend fun list(limit: Int): List<ContestCacheEntity> = rows.values.take(limit)

        override suspend fun byId(id: Long): ContestCacheEntity? = rows[id]

        override suspend fun clear() {
            rows.clear()
        }
    }

    private fun contest(id: Long, date: LocalDate = LocalDate.of(2026, 10, 4)) =
        ContestDto(id = id, name = "대회$id", contestDate = date)

    @Test
    fun `넣은 것을 그대로 되읽는다`() = runBlocking {
        val cache = RoomContestCache(FakeDao())

        cache.save(listOf(contest(1), contest(2)))

        assertEquals(listOf(1L, 2L), cache.list().map { it.id })
        assertEquals("대회1", cache.byId(1)?.name)
    }

    @Test
    fun `정렬용 날짜를 컬럼으로 꺼내 둔다`() = runBlocking {
        // JSON 안에만 있으면 SQL 이 정렬을 못 해서 오프라인 목록 순서가 서버와 어긋난다
        val dao = FakeDao()

        RoomContestCache(dao).save(listOf(contest(1, LocalDate.of(2026, 12, 25))))

        assertEquals("2026-12-25", dao.rows.getValue(1).contestDate)
    }

    @Test
    fun `저장 시각은 앱이 찍는다`() = runBlocking {
        // 서버가 준 값이 아니다 — P0 API 에 ETag·Last-Modified 가 없다(#105)
        val dao = FakeDao()

        RoomContestCache(dao, now = { 1_764_000_000_000 }).save(listOf(contest(1)))

        assertEquals(1_764_000_000_000, dao.rows.getValue(1).cachedAt)
    }

    @Test
    fun `못 읽는 행은 버리고 나머지를 준다`() = runBlocking {
        // 앱을 올리면서 DTO 가 바뀌면 옛 payload 가 남는다. 캐시 한 줄 때문에 화면이
        // 죽는 것보다 그 줄이 없는 편이 낫다 — 다음 성공 응답이 덮는다
        val dao = FakeDao()
        RoomContestCache(dao).save(listOf(contest(1)))
        dao.rows[2] = ContestCacheEntity(id = 2, payload = "{깨진 것}", contestDate = "2026-10-04", cachedAt = 0)

        val cache = RoomContestCache(dao)

        assertEquals(listOf(1L), cache.list().map { it.id })
        assertNull(cache.byId(2))
    }

    @Test
    fun `빈 응답으로는 아무것도 쓰지 않는다`() = runBlocking {
        // 서버가 빈 목록을 줬다고 이미 가진 캐시를 지우면, 필터 결과가 0건일 때
        // 오프라인 폴백이 통째로 날아간다
        val dao = FakeDao()
        RoomContestCache(dao).save(listOf(contest(1)))

        RoomContestCache(dao).save(emptyList())

        assertTrue("기존 행이 남아야 한다", dao.rows.containsKey(1))
    }
}

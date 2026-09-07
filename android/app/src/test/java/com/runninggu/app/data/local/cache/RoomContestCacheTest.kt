package com.runninggu.app.data.local.cache

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.ContestDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
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
 *
 * ③ list() 가 minOf 대신 maxOf 로 시각을 고른다  →  1개 실패
 *      되살린 목록은 가장 오래된 시각을 말한다 FAILED
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

    /** 저장 시각을 직접 정한 한 행. 시각이 섞였을 때 무엇을 말하는지 보려고 쓴다. */
    private fun entity(id: Long, cachedAt: Long) = ContestCacheEntity(
        id = id,
        payload = ApiJson.encodeToString(ContestDto.serializer(), contest(id)),
        contestDate = "2026-10-04",
        cachedAt = cachedAt,
    )

    @Test
    fun `되살린 목록은 가장 오래된 시각을 말한다`() = runBlocking {
        // 행마다 시각이 다를 수 있다 — byId 로 상세만 갱신되면 그 행만 새것이 된다.
        // 가장 새 값을 말하면 화면이 실제보다 최신인 것처럼 안내한다. 낡은 쪽으로 기운다 (#307)
        val dao = FakeDao()
        dao.rows[1] = entity(1, cachedAt = 1_000)
        dao.rows[2] = entity(2, cachedAt = 9_000)

        val cached = RoomContestCache(dao).list()

        assertEquals(Instant.ofEpochMilli(1_000), cached?.cachedAt)
    }

    @Test
    fun `못 읽는 행의 시각은 세지 않는다`() = runBlocking {
        // 버린 행의 시각까지 세면 화면에 없는 내용의 시각을 말하게 된다
        val dao = FakeDao()
        dao.rows[1] = ContestCacheEntity(id = 1, payload = "{깨진 것}", contestDate = "2026-10-04", cachedAt = 1_000)
        dao.rows[2] = entity(2, cachedAt = 9_000)

        val cached = RoomContestCache(dao).list()

        assertEquals(listOf(2L), cached?.contests?.map { it.id })
        assertEquals(Instant.ofEpochMilli(9_000), cached?.cachedAt)
    }

    @Test
    fun `되살릴 것이 없으면 null 이다`() = runBlocking {
        // 빈 목록을 주면 저장소가 "캐시가 있는데 0건" 으로 읽어 원래 오류를 안 던진다
        assertNull(RoomContestCache(FakeDao()).list())
    }

    @Test
    fun `상세도 저장 시각을 함께 준다`() = runBlocking {
        val dao = FakeDao()
        dao.rows[3] = entity(3, cachedAt = 5_000)

        assertEquals(Instant.ofEpochMilli(5_000), RoomContestCache(dao).byId(3)?.cachedAt)
    }

    @Test
    fun `넣은 것을 그대로 되읽는다`() = runBlocking {
        val cache = RoomContestCache(FakeDao())

        cache.save(listOf(contest(1), contest(2)))

        assertEquals(listOf(1L, 2L), cache.list()?.contests?.map { it.id })
        assertEquals("대회1", cache.byId(1)?.contest?.name)
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

        assertEquals(listOf(1L), cache.list()?.contests?.map { it.id })
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

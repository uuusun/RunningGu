package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.FavoriteApi
import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.data.remote.dto.PageDto
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 찜 창구. (API 명세 §7-C)
 *
 * 두 가지를 지킨다.
 *
 * 1. [FavoriteRepository.loadFavoriteIds] 는 **끝까지** 받는다 — 첫 장만 읽으면 21번째부터
 *    찜한 대회가 다른 화면에서 빈 하트로 보인다
 * 2. canonical id 가 없는 대회는 **서버를 부르지 않는다** — 번들 항목 id 를 숫자로 바꿔
 *    보내면 없는 대회를 찜하는 꼴이다
 */
class RemoteFavoriteRepositoryTest {

    private class FakeApi(private val pages: List<PageDto<ContestDto>>) : FavoriteApi {
        val requestedPages = mutableListOf<Int>()
        val added = mutableListOf<Long>()
        val removed = mutableListOf<Long>()

        override suspend fun list(page: Int, size: Int): PageDto<ContestDto> {
            requestedPages += page
            return pages.getOrElse(page) { emptyPage() }
        }

        override suspend fun add(contestId: Long) {
            added += contestId
        }

        override suspend fun remove(contestId: Long) {
            removed += contestId
        }
    }

    @Test
    fun `찜 id 를 마지막 장까지 받는다`() = runBlocking {
        val api = FakeApi(
            listOf(
                page(ids = listOf(1, 2), hasNext = true),
                page(ids = listOf(3), hasNext = false),
            ),
        )

        val ids = RemoteFavoriteRepository(api).loadFavoriteIds().getOrThrow()

        assertEquals(setOf("1", "2", "3"), ids)
        assertEquals(listOf(0, 1), api.requestedPages)
    }

    @Test
    fun `마지막 장이면 더 부르지 않는다`() = runBlocking {
        val api = FakeApi(listOf(page(ids = listOf(1), hasNext = false)))

        RemoteFavoriteRepository(api).loadFavoriteIds().getOrThrow()

        assertEquals(listOf(0), api.requestedPages)
    }

    @Test
    fun `목록은 비활성 대회를 걸러 내지 않는다`() = runBlocking {
        // 공개 목록과 달리 찜은 비활성도 유지한다 (§7-C 🔒 · 결정-46).
        val api = FakeApi(
            listOf(
                PageDto(
                    content = listOf(contest(id = 9, active = false)),
                    page = PageDto.PageMeta(totalElements = 1, hasNext = false),
                ),
            ),
        )

        val page = RemoteFavoriteRepository(api).list()

        assertEquals(1, page.contests.size)
        assertFalse(page.contests.first().active)
        assertEquals(9L, page.contests.first().serverId)
    }

    @Test
    fun `canonical id 가 없는 대회는 서버를 부르지 않는다`() = runBlocking {
        val api = FakeApi(emptyList())
        val repository = RemoteFavoriteRepository(api)

        val added = repository.add("roadrun-41543")
        val removed = repository.remove("roadrun-41543")

        assertTrue(added.isFailure)
        assertTrue(removed.isFailure)
        assertTrue(api.added.isEmpty())
        assertTrue(api.removed.isEmpty())
    }

    @Test
    fun `숫자 id 는 그대로 보낸다`() = runBlocking {
        val api = FakeApi(emptyList())
        val repository = RemoteFavoriteRepository(api)

        assertTrue(repository.add("42").isSuccess)
        assertTrue(repository.remove("42").isSuccess)

        assertEquals(listOf(42L), api.added)
        assertEquals(listOf(42L), api.removed)
    }
}

private fun emptyPage() = PageDto<ContestDto>(
    content = emptyList(),
    page = PageDto.PageMeta(hasNext = false),
)

private fun page(ids: List<Long>, hasNext: Boolean) = PageDto(
    content = ids.map { contest(id = it) },
    page = PageDto.PageMeta(totalElements = ids.size.toLong(), hasNext = hasNext),
)

private fun contest(id: Long, active: Boolean = true) = ContestDto(
    id = id,
    active = active,
    name = "대회 $id",
    contestDate = LocalDate.of(2026, 10, 11),
)

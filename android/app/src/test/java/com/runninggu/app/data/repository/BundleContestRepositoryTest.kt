package com.runninggu.app.data.repository

import com.runninggu.app.data.local.ContestBundle
import com.runninggu.app.data.model.Contest
import com.runninggu.app.domain.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 오프라인 폴백이 §3-1 조회 규칙과 같은 목록을 내는지. (SPEC §6.1 · NFR-1)
 *
 * 온라인이면 서버가 이기지만, 오프라인에서 **다른 목록이 나오면 사용자는 버그로 느낀다.**
 */
class BundleContestRepositoryTest {

    private val today = LocalDate.of(2026, 6, 1)

    private fun bundle(): List<Contest> =
        ContestBundle.parse(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("races.json"))
                .bufferedReader().use { it.readText() },
        ).contests

    private fun repo(source: List<Contest> = bundle()) =
        BundleContestRepository(source = { source }, clock = { today })

    @Test
    fun `오늘 이전 대회는 빼고 날짜 오름차순이다`() = runBlocking {
        // §3-1 — contest_date >= 오늘(KST) 고정 · 정렬 (contestDate, id) ASC
        val page = repo().list(ContestFilter(size = 50))

        assertTrue(page.contests.none { it.date.isBefore(today) })
        assertEquals(page.contests.map { it.date }.sorted(), page.contests.map { it.date })
    }

    @Test
    fun `커서로 다음 페이지를 이어 받는다`() = runBlocking {
        val r = repo()
        val first = r.list(ContestFilter(size = 5))

        assertEquals(5, first.contests.size)
        assertTrue(first.hasNext)

        val second = r.list(ContestFilter(size = 5), cursor = first.nextCursor)

        assertEquals(5, second.contests.size)
        // 같은 대회가 두 번 나오면 안 된다
        assertTrue((first.contests.map { it.id } intersect second.contests.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `마지막 페이지는 커서가 없다`() = runBlocking {
        val all = repo().list(ContestFilter(size = 500))

        assertFalse(all.hasNext)
        assertNull(all.nextCursor)
    }

    @Test
    fun `지역 필터는 AND, 종목 필터는 OR 이다`() = runBlocking {
        val r = repo()
        val seoul = r.list(ContestFilter(regions = listOf("서울"), size = 500))
        assertTrue(seoul.contests.all { it.region == "서울" })

        val full = r.list(ContestFilter(events = listOf(EventType.FULL, EventType.HALF), size = 500))
        assertTrue(
            full.contests.all { c ->
                c.eventTypes.any { it == EventType.FULL || it == EventType.HALF }
            },
        )

        // 둘을 같이 주면 AND
        val both = r.list(
            ContestFilter(regions = listOf("서울"), events = listOf(EventType.FULL), size = 500),
        )
        assertTrue(both.contests.all { it.region == "서울" && EventType.FULL in it.eventTypes })
    }

    @Test
    fun `검색은 대회명과 장소와 지역을 본다`() = runBlocking {
        val hit = repo().list(ContestFilter(query = "마라톤", size = 500))

        assertTrue(hit.contests.isNotEmpty())
        assertTrue(
            hit.contests.all {
                listOf(it.name, it.venue, it.region).any { f -> f.contains("마라톤") }
            },
        )
    }

    @Test
    fun `접수중만 보기는 오늘 기준으로 다시 계산한다`() = runBlocking {
        // 번들의 regStatus 를 그대로 믿으면 안 된다 (SPEC §5.5)
        val open = repo().list(ContestFilter(openOnly = true, size = 500))

        assertTrue(
            open.contests.all { c ->
                c.regEnd == null || !c.regEnd!!.isBefore(today)
            },
        )
    }

    @Test
    fun `월간 점 집계는 목록과 같은 필터를 쓴다`() = runBlocking {
        val r = repo()
        val counts = r.dailyCounts(2026, 8, ContestFilter(regions = listOf("서울")))
        val list = r.list(ContestFilter(regions = listOf("서울"), size = 500))

        val fromList = list.contests
            .filter { it.date.year == 2026 && it.date.monthValue == 8 }
            .groupingBy { it.date }.eachCount()

        // 점과 목록이 어긋나면 사용자가 바로 알아챈다 (§3-2 부록 G-1)
        assertEquals(fromList, counts)
    }

    @Test
    fun `마감 임박은 접수중만 마감일 순으로 준다`() = runBlocking {
        val soon = repo().closingSoon(limit = 4).items

        assertTrue(soon.size <= 4)
        assertTrue(soon.all { it.contest.regEnd != null })
        assertEquals(soon.map { it.contest.regEnd }.sortedBy { it }, soon.map { it.contest.regEnd })
        // dDayApply 는 마감일 − 오늘
        soon.forEach { assertTrue(it.dDayApply!! >= 0) }
    }

    @Test
    fun `번들에는 canonical id 가 없어 서버 상세를 흉내내지 않는다`() = runBlocking {
        // 번들 id 는 크롤 원천의 externalId 라 서버 상세와 같은 키가 아니다 (#52 리뷰).
        // 조용히 빈 값을 주면 화면이 Empty 로 잘못 그린다
        try {
            repo().detail(153L)
            error("예외가 나야 한다")
        } catch (e: NoSuchElementException) {
            assertTrue(e.message!!.contains("canonical id"))
        }
    }

    @Test
    fun `오프라인 상세는 화면 키로 찾는다`() = runBlocking {
        val any = repo().list().contests.first()

        assertEquals(any.id, repo().findByKey(any.id)?.id)
        assertNull(repo().findByKey("존재하지-않음"))
    }
}

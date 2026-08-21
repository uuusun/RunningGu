package com.runninggu.app.data.repository

import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.data.remote.ApiException
import java.io.IOException
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.domain.dDay
import com.runninggu.app.domain.regStatusOf
import com.runninggu.app.domain.today
import java.time.LocalDate

/**
 * 번들·캐시로 목록을 만드는 구현. (SPEC §6.1 · NFR-1)
 *
 * 두 가지 용도를 겸한다.
 * 1. **오프라인 폴백** — 서버를 못 부를 때 assets 번들로 같은 화면을 그린다
 * 2. **백엔드 준비 전 스텁** — 화면이 `SampleData` 를 벗어나 Repository 로 붙을 수 있게 한다
 *
 * §3-1 의 조회 규칙(오늘 이후 · 정렬 · 필터)을 앱에서 다시 구현한다. 서버 로직을 두 벌 두는 건
 * 원래 피해야 하지만, **오프라인에서도 같은 목록이 나와야 하므로** 폴백에는 불가피하다.
 * 온라인에서는 서버 응답이 이긴다(§6.1).
 */
class BundleContestRepository(
    private val source: () -> List<Contest>,
    private val clock: () -> LocalDate = ::today,
) : ContestRepository {

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage {
        val matched = matching(filter)
        val from = cursor?.toIntOrNull() ?: 0
        val page = matched.drop(from).take(filter.size)
        val next = from + page.size
        return ContestPage(
            contests = page,
            // 서버 커서는 불투명 문자열이다. 여기서는 오프셋을 쓰되 **형식을 흉내내지 않는다** —
            // 화면이 커서를 해석하지 않는지 검증하는 효과도 있다.
            nextCursor = next.takeIf { it < matched.size }?.toString(),
            hasNext = next < matched.size,
        )
    }

    override suspend fun dailyCounts(year: Int, month: Int, filter: ContestFilter): Map<LocalDate, Int> =
        matching(filter.copy(date = null))
            .filter { it.date.year == year && it.date.monthValue == month }
            .groupingBy { it.date }
            .eachCount()

    override suspend fun closingSoon(limit: Int): List<ClosingSoon> {
        val now = clock()
        return matching(ContestFilter(openOnly = true))
            .filter { it.regEnd != null }
            .sortedBy { it.regEnd }
            .take(limit)
            .map { ClosingSoon(it, dDay(it.regEnd!!, now)) }
    }

    /**
     * 번들에는 canonical id 가 없다 — 항상 찾지 못한다. (#52 리뷰)
     *
     * 번들 항목은 크롤 원천의 externalId 만 갖고 있어 서버 상세와 같은 키로 조회할 수 없다.
     * 오프라인에서 상세를 그릴 때는 목록에서 이미 받은 [Contest] 를 쓰거나 [findByKey] 를 쓴다.
     */
    override suspend fun detail(id: Long): Contest =
        throw NoSuchElementException("번들에는 canonical id 가 없다: $id")

    /**
     * 번들에는 축제 데이터가 없다. (§3-5)
     *
     * **빈 목록을 주지 않는다** — 그러면 화면이 "대회 기간에 열리는 인근 축제가 없어요" 를
     * 그려서, 실제로는 못 불러온 것을 없다고 말하게 된다. 실패로 올려 재시도를 띄운다.
     */
    override suspend fun festivals(id: Long): List<NearbyFestival> =
        throw ApiException.Network(IOException("오프라인이라 인근 축제를 부를 수 없다"))

    /** 오프라인 표시용 — 화면 키(번들 id)로 찾는다. **서버 상세와 다른 경로다.** */
    suspend fun findByKey(key: String): Contest? = source().firstOrNull { it.id == key }

    /** §3-1 규칙 — `contestDate >= 오늘(KST)` 고정, 정렬 `(date, id) ASC`, 필터는 AND(events 만 OR). */
    private fun matching(filter: ContestFilter): List<Contest> {
        val now = clock()
        return source()
            .asSequence()
            .filter { !it.date.isBefore(now) }
            .filter { filter.date == null || it.date == filter.date }
            .filter { filter.regions.isEmpty() || it.region in filter.regions }
            .filter { c -> filter.events.isEmpty() || c.eventTypes.any { it in filter.events } }
            .filter { !filter.openOnly || it.statusOn(now) == RegistrationStatus.OPEN }
            .filter { filter.query.isNullOrBlank() || it.matches(filter.query) }
            .sortedWith(compareBy({ it.date }, { it.id }))
            .toList()
    }

    /** 접수 상태는 저장값이 아니라 **오늘 기준 재계산**이다. (SPEC §5.5) */
    private fun Contest.statusOn(now: LocalDate) =
        regStatusOf(regStart, regEnd, regStatusFallback, now)

    /** 대회명 + 장소 + 지역 부분 일치. (§3-1 `q`) */
    private fun Contest.matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return name.lowercase().contains(q) ||
            venue.lowercase().contains(q) ||
            region.lowercase().contains(q)
    }
}

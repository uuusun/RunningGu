package com.runninggu.app.data.repository

import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toContest
import com.runninggu.app.domain.EventType
import java.time.LocalDate

/**
 * 대회 조회 창구. (API 명세 §3)
 *
 * 화면은 이 인터페이스만 본다 — 서버든 번들이든 출처를 모른다.
 * 실패는 `ApiException` 으로 던지고 ViewModel 이 `UiState.Error` 로 옮긴다(§0-3).
 */
interface ContestRepository {

    /** 목록 한 페이지. [ContestPage.nextCursor] 를 그대로 다시 넘기면 다음 페이지다. */
    suspend fun list(filter: ContestFilter = ContestFilter(), cursor: String? = null): ContestPage

    /** 월간 뷰 점 집계. 목록과 같은 필터를 넘겨야 어긋나지 않는다. */
    suspend fun dailyCounts(year: Int, month: Int, filter: ContestFilter = ContestFilter()): Map<LocalDate, Int>

    /** 홈 마감 임박. `dDayApply` 가 필요하므로 [ClosingSoon] 으로 감싼다. */
    suspend fun closingSoon(limit: Int = ContestApi.DEFAULT_CLOSING_SOON_LIMIT): List<ClosingSoon>

    /**
     * 대회 상세. **canonical id 만 받는다.** (§3-4)
     *
     * 번들 항목의 id 는 크롤 원천 문자열이라 여기 넣을 수 없다 — 타입이 그걸 막는다.
     * 호출부는 [Contest.serverId] 를 쓰고, null 이면 서버를 부르지 않는다.
     */
    suspend fun detail(id: Long): Contest
}

/**
 * 목록 필터. (§3-1)
 *
 * `events` 내부만 OR 이고 나머지는 AND 로 결합한다 — 서버 규칙이다.
 */
data class ContestFilter(
    val query: String? = null,
    val events: List<EventType> = emptyList(),
    val openOnly: Boolean = false,
    val regions: List<String> = emptyList(),
    /** 월간 뷰에서 고른 날짜. 그 일자만 본다. */
    val date: LocalDate? = null,
    val size: Int = ContestApi.DEFAULT_PAGE_SIZE,
)

data class ContestPage(
    val contests: List<Contest> = emptyList(),
    /** 불투명 커서. null 이면 마지막 페이지다. 앱은 해석하지 않는다(§0-4). */
    val nextCursor: String? = null,
    val hasNext: Boolean = false,
)

/** 마감 임박 항목 — 대회 + 마감까지 남은 일수. (§3-3) */
data class ClosingSoon(
    val contest: Contest,
    /** `applyEnd − 오늘`. 서버가 안 주면 null 이고 화면은 배지를 숨긴다. */
    val dDayApply: Int?,
)

/** 서버 구현. */
class RemoteContestRepository(private val api: ContestApi) : ContestRepository {

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage = apiCall {
        val dto = api.list(
            query = filter.query?.takeIf { it.isNotBlank() },
            events = filter.events.map { it.toServerName() }.ifEmpty { null },
            openOnly = filter.openOnly.takeIf { it },
            regions = filter.regions.ifEmpty { null },
            date = filter.date?.toString(),
            cursor = cursor,
            size = filter.size,
        )
        ContestPage(
            contests = dto.items.map { it.toContest() },
            nextCursor = dto.nextCursor,
            hasNext = dto.hasNext,
        )
    }

    override suspend fun dailyCounts(year: Int, month: Int, filter: ContestFilter): Map<LocalDate, Int> =
        apiCall {
            api.dailyCounts(
                year = year,
                month = month,
                query = filter.query?.takeIf { it.isNotBlank() },
                events = filter.events.map { it.toServerName() }.ifEmpty { null },
                openOnly = filter.openOnly.takeIf { it },
                regions = filter.regions.ifEmpty { null },
            ).counts.associate { it.date to it.count }
        }

    override suspend fun closingSoon(limit: Int): List<ClosingSoon> = apiCall {
        api.closingSoon(limit).items.map { ClosingSoon(it.toContest(), it.dDayApply) }
    }

    override suspend fun detail(id: Long): Contest = apiCall {
        api.detail(id).toContest()
    }
}

/** 도메인 종목 → 서버 enum. (부록 C — 도메인은 한국어 라벨을 쓴다) */
internal fun EventType.toServerName(): String = when (this) {
    EventType.FULL -> "FULL"
    EventType.HALF -> "HALF"
    EventType.TEN_K -> "K10"
    EventType.FIVE_K -> "K5"
}

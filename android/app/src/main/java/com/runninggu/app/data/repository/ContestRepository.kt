package com.runninggu.app.data.repository

import com.runninggu.app.data.local.cache.ClosingSoonCache
import com.runninggu.app.data.local.cache.ContestCache
import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toDomain
import com.runninggu.app.data.remote.mapper.toServerName
import com.runninggu.app.data.remote.mapper.toContest
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.dDay
import com.runninggu.app.domain.today
import java.time.Instant
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

    /**
     * 홈 마감 임박. `dDayApply` 가 필요하므로 [ClosingSoon] 으로 감싼다.
     *
     * **출처를 함께 준다**([ClosingSoonResult.cachedAt]). 오프라인에서 되살린 목록은 화면이
     * 마지막 성공본이라고 말해야 하는데, `List` 만 넘기면 그 사실이 여기서 사라진다
     * (SPEC §6.1 캐시 출처 표기 · 이슈 #276).
     */
    suspend fun closingSoon(limit: Int = ContestApi.DEFAULT_CLOSING_SOON_LIMIT): ClosingSoonResult

    /**
     * 대회 상세. **canonical id 만 받는다.** (§3-4)
     *
     * 번들 항목의 id 는 크롤 원천 문자열이라 여기 넣을 수 없다 — 타입이 그걸 막는다.
     * 호출부는 [Contest.serverId] 를 쓰고, null 이면 서버를 부르지 않는다.
     */
    suspend fun detail(id: Long): Contest

    /**
     * 대회 인근 축제. (§3-5)
     *
     * **상세 본문과 따로 부른다** — 외부 API 경유라 느리고, 실패해도 대회 상세는 그대로
     * 보여야 한다(§4.6 부분 실패). 빈 목록은 정상이고 화면이 Empty 로 그린다.
     *
     * 대회 좌표가 없으면 서버가 `409 CONTEST_LOCATION_UNAVAILABLE` 을 준다.
     */
    suspend fun festivals(id: Long): List<NearbyFestival>
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

/**
 * 마감 임박 조회 결과. (§3-3 · SPEC §6.1 · 이슈 #276)
 *
 * @param items 서버가 준 순서 그대로. 앱은 다시 고르지도 정렬하지도 않는다.
 * @param cachedAt **캐시로 되살린 것이면** 앱이 그 응답을 저장한 시각, 서버에서 막 받은
 *   것이면 `null`. 화면은 이 값이 있을 때만 "언제 것" 인지를 함께 그린다.
 */
data class ClosingSoonResult(
    val items: List<ClosingSoon> = emptyList(),
    val cachedAt: Instant? = null,
)

/** 마감 임박 항목 — 대회 + 마감까지 남은 일수. (§3-3) */
data class ClosingSoon(
    val contest: Contest,
    /** `applyEnd − 오늘`. 서버가 안 주면 null 이고 화면은 배지를 숨긴다. */
    val dDayApply: Int?,
)

/**
 * 서버 구현. 성공 응답을 [cache] 에 남기고, **연결이 안 될 때만** 그것을 되살린다.
 * (SPEC §6.1 · §9.3 · 매핑표 S1·S3 오프라인 · 이슈 #105)
 *
 * ## 되살리는 조건이 네트워크 실패 하나인 이유
 *
 * 서버가 답을 준 것(`4xx`·`5xx`)은 **연결이 살아 있다는 뜻**이다. 그때 낡은 목록을 대신
 * 그리면 사용자는 지금 서버가 말한 것과 다른 화면을 보게 되고, 무엇이 최신인지 알 방법이
 * 없다. 폴백은 "볼 수 있는 게 아무것도 없을 때" 만 값어치가 있다.
 *
 * 캐시가 비어 있으면 **원래 오류를 그대로 던진다.** 빈 목록으로 바꾸면 "대회가 없다" 가
 * 되어 사실과 다르다.
 */
class RemoteContestRepository(
    private val api: ContestApi,
    /** 없으면 폴백 없이 서버만 본다. 캐시를 안 쓰는 테스트가 이 상태다. */
    private val cache: ContestCache? = null,
    /**
     * 마감임박 snapshot. [cache] 와 따로 두는 이유는 담는 것이 다르기 때문이다 —
     * 이쪽은 개별 대회가 아니라 **서버가 고른 결과와 그 순서**다(#276).
     */
    private val closingSoonCache: ClosingSoonCache? = null,
) : ContestRepository {

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage = withCacheFallback(
        remote = {
            val dto = api.list(
                query = filter.query?.takeIf { it.isNotBlank() },
                events = filter.events.map { it.toServerName() }.ifEmpty { null },
                openOnly = filter.openOnly.takeIf { it },
                regions = filter.regions.ifEmpty { null },
                date = filter.date?.toString(),
                cursor = cursor,
                size = filter.size,
            )
            cache?.save(dto.items)
            ContestPage(
                contests = dto.items.map { it.toContest() },
                nextCursor = dto.nextCursor,
                hasNext = dto.hasNext,
            )
        },
        cached = {
            // **첫 장에서만 되살린다.** 다음 장을 캐시로 채우면 이미 본 대회가 다시 붙는다 —
            // 커서는 서버 것이라 캐시가 어디에 이어 붙어야 할지 알 수 없다
            if (cursor != null) {
                null
            } else {
                cache?.list().orEmpty().takeIf { it.isNotEmpty() }?.let { cached ->
                    // 오프라인 목록은 **더 볼 것이 없다.** 커서를 지어내면 [더 보기] 가 헛돈다
                    ContestPage(contests = cached.map { it.toContest() }, nextCursor = null, hasNext = false)
                }
            }
        },
    )

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

    /**
     * 마감임박. 성공 응답을 [closingSoonCache] 에 snapshot 으로 남기고, **연결이 안 될 때만**
     * 되살린다. (SPEC §6.1 `cached_closing_soon` · 매핑표 S1 오프라인 · 이슈 #276)
     *
     * ## 되살릴 때 서버 응답과 달라지는 것 하나
     *
     * `dDayApply` 를 **다시 센다.** 저장된 값을 그대로 쓰면 하루만 지나도 거짓이 되고,
     * 이미 마감된 대회를 "마감 D-2" 로 보여주게 된다. 그래서 접수가 끝난 항목은 아예 뺀다.
     *
     * 빼고 나서 0건이면 **정상 빈 상태**다(빈 목록을 그대로 돌려준다). 되살릴 snapshot 이
     * 아예 없는 것과는 다르다 — 그때는 원래 네트워크 오류를 그대로 던져서 화면이
     * [다시 시도] 를 띄우게 한다.
     */
    override suspend fun closingSoon(limit: Int): ClosingSoonResult = try {
        apiCall {
            val items = api.closingSoon(limit).items
            closingSoonCache?.save(items)
            ClosingSoonResult(items.map { ClosingSoon(it.toContest(), it.dDayApply) })
        }
    } catch (e: ApiException.Network) {
        val snapshot = closingSoonCache?.snapshot() ?: throw e
        val today = today()
        ClosingSoonResult(
            items = snapshot.contests
                // 접수가 이미 끝난 것은 뺀다. `applyEnd` 가 없으면 끝났는지 알 수 없으므로 남긴다 —
                // 서버가 마감임박으로 골라 준 항목이라 "모르니까 버린다" 보다 남기는 쪽이 맞다
                .filter { it.applyEnd == null || !it.applyEnd.isBefore(today) }
                .map { ClosingSoon(it.toContest(), it.applyEnd?.let { end -> dDay(end, today) }) },
            cachedAt = Instant.ofEpochMilli(snapshot.cachedAt),
        )
    }

    override suspend fun detail(id: Long): Contest = withCacheFallback(
        remote = {
            val dto = api.detail(id)
            cache?.save(listOf(dto))
            dto.toContest()
        },
        // 목록에서 한 번이라도 본 대회만 있다. 못 본 것은 폴백이 없다
        cached = { cache?.byId(id)?.toContest() },
    )

    /**
     * 서버를 부르고, **연결 자체가 안 됐을 때만** 캐시를 본다.
     *
     * [cached] 가 `null` 을 주면(캐시가 비었거나 되살릴 자리가 아니면) 원래 오류를 던진다.
     * 목록·상세 말고 다른 조회에는 붙이지 않았다 — 캘린더 집계·마감임박은 SPEC §6.1 의
     * 캐시 대상이 아니다.
     */
    private suspend fun <T> withCacheFallback(
        remote: suspend () -> T,
        cached: suspend () -> T?,
    ): T = try {
        apiCall { remote() }
    } catch (e: ApiException.Network) {
        cached() ?: throw e
    }

    override suspend fun festivals(id: Long): List<NearbyFestival> = apiCall {
        api.festivals(id).toDomain()
    }
}


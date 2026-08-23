package com.runninggu.app.ui.model

import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.domain.regStatusOf
import com.runninggu.app.domain.today
import java.time.LocalDate
import com.runninggu.app.domain.dDay as domainDDay
import com.runninggu.app.domain.dDayLabel as domainDDayLabel

/**
 * 화면에서 쓰는 대회 요약. 홈(S1)·캘린더(S2)가 공유한다.
 *
 * TODO(AP-04·AP-14): 도메인/데이터 레이어가 들어오면 그쪽 모델로 대체하고,
 * 여기는 UI 전용 매핑만 남긴다.
 */
data class RaceSummary(
    /** 화면·내비게이션 키. 번들 항목은 크롤 원천 문자열이라 서버 호출에 쓰지 않는다. */
    val id: String,
    /**
     * canonical `CONTEST.id`. 서버에서 온 대회만 갖는다. (API 명세 §3-1)
     *
     * 동선 생성·찜·상세처럼 서버를 부르는 동작은 이 값이 있을 때만 한다 (#66 리뷰).
     */
    val serverId: Long? = null,
    val name: String,
    val region: String,
    val venue: String,
    val date: LocalDate,
    val startTime: String,
    val regStart: LocalDate?,
    val regEnd: LocalDate?,
    val eventTypes: List<String>,
    val source: String,
    /** 원본 스냅샷을 확인한 날짜. 카드에 "{source} · 확인 MM.DD"로 표기한다. */
    val checked: LocalDate?,
    /** 원본 스냅샷의 접수 상태. 날짜 정보가 없을 때만 쓴다. (서버 `regStatusFallback`) */
    val regStatusFallback: RegistrationStatus? = null,
    /**
     * 아래 두 필드는 S3 상세에만 나온다. 카드(S1·S2)는 쓰지 않으므로 없을 수 있다.
     * (API 명세 §3-4 — 상세 응답에서만 내려온다)
     */
    val organizer: String? = null,
    val officialUrl: String? = null,
    /**
     * 대회장 좌표. **S3 상세에서만 온다.** (API 명세 §3-4 · SPEC §4.6)
     *
     * canonical 153건은 좌표 누락 0건이지만 nullable 로 방어한다 — 원천이 늘면 빠질 수
     * 있고, 없는 좌표를 `(0.0, 0.0)` 으로 지어내면 기니만 앞바다를 조회하게 된다(#136 리뷰).
     *
     * S6 숙소 조회와 S7 후보 시트가 이 값을 기준 좌표로 쓴다(§4.9 · §4.10).
     */
    val lat: Double? = null,
    val lng: Double? = null,
    /**
     * 원천에서 사라진 대회인가. (API 명세 §3-4 · SPEC 결정-46)
     *
     * 공개 목록(S2)에는 `active=true` 만 오므로 **여기서 false 를 보는 곳은 찜 목록과
     * 저장 동선에서 들어온 상세뿐**이다. 참조를 지키려고 삭제하지 않고 남긴 것이라
     * 화면은 흐리게 그리고 "정보 제공 종료" 를 붙인다.
     */
    val active: Boolean = true,
)

/**
 * 카드·상세를 흐리게 그릴 것인가. (SPEC §4.13 · API 명세 §7-C 🔒)
 *
 * 지난 대회와 비활성 대회를 **같은 규칙으로** 다룬다 — 둘 다 "지금은 신청할 수 없는
 * 대회" 라 사용자가 구분할 이유가 없다. 판정은 서버가 아니라 클라가 한다(§7-C 🔒).
 *
 * 접수 마감(`registrationStatus`)과는 다르다. 마감은 카드를 흐리게 하지 않고 텍스트
 * 색만 낮춘다 — 아직 열릴 수 있는 대회이기 때문이다.
 */
fun RaceSummary.isDimmed(today: LocalDate = today()): Boolean =
    !active || date.isBefore(today)

/**
 * 대회장 좌표가 있는가. (SPEC §4.6 · `Contest.hasLocation` 과 같은 판정)
 *
 * 좌표가 없으면 S6 숙소·S7 후보를 대회장 기준으로 조회할 수 없다. P0 에서는 동선 만들기
 * CTA 를 막는 근거로 쓴다 — 좌표 전용 안내 UX 는 P1 이다(§4.6).
 */
val RaceSummary.hasLocation: Boolean
    get() = lat != null && lng != null

/**
 * 대회일까지 남은 일수. 오늘이면 0, 지났으면 음수. (SPEC §4.6 · KST 기준 §6.6)
 *
 * 계산은 `domain` 이 한다 — 기본값이 KST 기준 오늘이라야 해외에서도 어긋나지 않는다.
 */
fun RaceSummary.dDay(today: LocalDate = today()): Int = domainDDay(date, today)

/** "D-18" · "D-day" · "D+2" 표기. (SPEC §4.6) */
fun RaceSummary.dDayLabel(today: LocalDate = today()): String = domainDDayLabel(date, today)

/**
 * 접수 상태. (SPEC §5.5)
 *
 * 규칙은 `domain` 이 갖는다 — dDay 와 같은 이유로, 기본값이 KST 기준 오늘이라야 어긋나지 않는다.
 */
fun RaceSummary.registrationStatus(today: LocalDate = today()): RegistrationStatus =
    regStatusOf(regStart, regEnd, regStatusFallback, today)

/**
 * S3 대회 인근 축제. (SPEC §4.6 M3 · API 명세 §3-5)
 *
 * 서버가 대회일 ±14일 ∧ 반경 40km로 걸러 거리순 6건을 내려준다 — 앱은 거르지 않고 그대로 그린다.
 */
data class NearbyFestival(
    val contentId: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** 대회장 기준 직선거리(km). 카드에 "대회장 {d.d}km"로 표기한다. */
    val distanceKm: Double,
    val imageUrl: String?,
    val address: String,
)

/** 홈 축제 캐러셀 항목. 출처는 한국관광공사 고정 표기. (NFR-7) */
data class FestivalSummary(
    val id: String,
    val name: String,
    val region: String,
    val period: String,
    val isOngoing: Boolean,
)

/** 필터에 쓰는 종목. (SPEC §4.5 · 결정-12) */
val EVENT_TYPES = listOf("5K", "10K", "하프", "풀")

/** 17개 시도. (SPEC §4.5 지역 필터) */
val REGIONS = listOf(
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
    "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
)

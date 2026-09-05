package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Contextual
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * `POST /api/itineraries/generate` 요청. (API 명세 §5-1)
 *
 * 날짜는 **KST 비즈니스 날짜** 문자열이다(`YYYY-MM-DD`) — timestamp 가 아니다(AGENTS 2장-4).
 * `hotel` 은 null 을 허용한다. "숙소 없이 추천받기" 면 서버가 대회장 중심으로 슬롯을 채운다
 * (SPEC §4.9).
 */
@Serializable
data class GenerateItineraryRequestDto(
    /** canonical `CONTEST.id`. **숫자다** — 번들 externalId 를 보내면 안 된다(#66 리뷰). */
    val contestId: Long,
    val startDate: String,
    val endDate: String,
    /** `FULL|HALF|K10|K5` — 부록 C 계약 값이다. 앱 enum 이름이 아니다. */
    val event: String,
    /** 1개 이상. 0개면 화면이 CTA 를 막는다 (§4.8). */
    val themes: List<String>,
    val hotel: HotelDto? = null,
)

/** 요청·응답 공통 숙소 표기. (§5-1) */
@Serializable
data class HotelDto(val name: String, val lat: Double, val lng: Double)

/**
 * `POST /api/itineraries/generate` 응답. (API 명세 §5-1 · SPEC 결정-41)
 *
 * 동선 생성은 **서버 단일 주체**다. 앱은 이 DTO 를 받아 표시하고 저장 전 USER 블록만 편집한다.
 */
@Serializable
data class GenerateItineraryResponse(
    val title: String,
    /** 요청과 같은 계약 값 — `FULL|HALF|K10|K5` 다. */
    val event: String,
    val recovery: RecoveryDto? = null,
    val days: List<DayDto> = emptyList(),
    // §5-2 저장 요청이 이 응답 구조를 그대로 쓴다 — 버리면 저장·재생성 요청을 못 만든다.
    // 저장에 필요한 값이라 **필수**다. nullable 로 두면 빠진 응답이 조용히 통과한다(#66 리뷰)
    val contestId: Long,
    val themes: List<String>,
    val startDate: String,
    val endDate: String,
    /** 숙소 없이 추천받은 동선은 null 이다. (§4.9) */
    val hotel: HotelDto? = null,
)

@Serializable
data class RecoveryDto(val label: String, val note: String)

@Serializable
data class DayDto(
    val dayIndex: Int,
    val date: String,
    val dayLabel: String,
    /** 회복일인가. 일자 탭과 지도 핀 색을 가른다. (API 명세 §5-1) */
    val recovery: Boolean = false,
    val note: String = "",
    val blocks: List<BlockDto> = emptyList(),
    /**
     * 저장된 동선의 일자 id. **상세(§5-5)에만 온다.**
     *
     * 생성(§5-1)은 DB 저장이 없는 DTO 라 id 가 없다. 상세는 "5-1 응답 구조 + `id`" 이므로
     * 트리 DTO 를 따로 만들지 않고 여기서 nullable 로 받는다 — 두 벌이면 필드가 갈라진다.
     */
    val id: Long? = null,
)

/**
 * 일정 블록.
 *
 * 외부 POI 조회가 실패하면 서버가 `placeName`·`lat`·`lng` 를 null 로 강등하되 생성은 성공시킨다
 * (API 명세 §5-1 · NFR-3). 그래서 장소 없는 블록이 정상적으로 올 수 있다.
 */
@Serializable
data class BlockDto(
    val startTime: String,
    val title: String,
    val category: String,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val description: String = "",
    val blockType: String = "USER",
    val systemManaged: Boolean = false,
    /**
     * 저장된 블록 id. **상세(§5-5)에만 온다.** 없으면 매퍼가 응답 안에서만 쓰는 id 를 만든다.
     *
     * 복원한 동선을 편집할 때는 이 id 가 진짜다 — 만들어 낸 id 로 편집하면 서버가 어느
     * 블록인지 못 찾는다(SPEC §6.3).
     */
    val id: Long? = null,
    /** 저장 시 정렬 순서. 상세는 ASC 로 정렬해서 준다 (§5-5). */
    val orderNo: Int? = null,
)

/**
 * 블록 추가 요청. (§5-7)
 *
 * **`startTime` 기본값이 계약에 박혀 있다** 🔒(`"13:00"`). 안 보내면 서버가 이 값을
 * 쓰므로 앱도 같은 값을 기본으로 둔다 — 여기서 다른 값을 쓰면 화면이 보여 준 시각과
 * 저장된 시각이 갈린다.
 *
 * 장소가 없는 블록도 정상이다(§5-1 과 같다). 그래서 `placeName`·좌표가 전부 nullable 이다.
 */
@Serializable
data class BlockCreateRequestDto(
    val title: String,
    val category: String,
    val startTime: String = DEFAULT_BLOCK_START_TIME,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val description: String = "",
)

/** `POST .../blocks` 응답. 새 블록의 id 와 끝에 붙은 순서만 온다 (§5-7). */
@Serializable
data class BlockCreatedDto(val blockId: Long, val orderNo: Int)

/**
 * 블록 수정 요청. (§5-8)
 *
 * **보낸 필드만 반영된다.** 그래서 전부 nullable 이고 기본값이 `null` 이다 — 안 건드릴
 * 필드를 현재 값으로 채워 보내면, 그 사이 서버 값이 바뀌었을 때 덮어쓰게 된다.
 *
 * `null` 을 "이 필드를 비워 달라" 는 뜻으로 쓸 수 없다는 뜻이기도 하다. 장소를 지우는
 * 계약은 §5-8 에 없다 — 필요해지면 계약부터다.
 */
@Serializable
data class BlockPatchRequestDto(
    val startTime: String? = null,
    val title: String? = null,
    val category: String? = null,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val description: String? = null,
)

/**
 * 블록 순서 변경 요청. (§5-10)
 *
 * **해당 일자의 USER 블록 전체 집합과 정확히 일치해야 한다.** 일부만 보내거나 RACE 를
 * 섞으면 `400 BLOCK_SET_MISMATCH` 다. 부분 갱신이 아니라 전체 교체다.
 */
@Serializable
data class BlockOrderRequestDto(val blockIds: List<Long>)

/** `PUT .../blocks/order` 응답. 그 일자의 **전체** 블록이 `orderNo` 오름차순으로 온다 (§5-10). */
@Serializable
data class DayBlocksDto(val dayId: Long, val blocks: List<BlockDto> = emptyList())

/** 블록 추가 기본 시각 🔒(§5-7). 서버 기본값과 같아야 한다. */
const val DEFAULT_BLOCK_START_TIME: String = "13:00"

/**
 * `GET /api/itineraries` 목록 항목. (API 명세 §5-4)
 *
 * **상세(§5-5)와 다른 모양이다.** 목록은 카드가 쓰는 값만 오고 `days`·`blocks` 는 없다 —
 * 카드 하나를 그리려고 트리 전체를 받지 않는다.
 *
 * [needsRegeneration] 은 저장 snapshot 과 현재 canonical 대회가 다를 때 참이다. 이름만
 * 바뀌거나 [active] 만 달라진 것으로는 참이 되지 않는다(§5-4).
 */
@Serializable
data class ItinerarySummaryDto(
    val id: Long,
    val title: String,
    val contestId: Long,
    val contestName: String,
    val event: String,
    val region: String? = null,
    val recovery: RecoveryDto? = null,
    @Contextual val startDate: LocalDate,
    @Contextual val endDate: LocalDate,
    val placeCount: Int = 0,
    @Contextual val createdAt: Instant? = null,
    /** 대회가 원천에서 사라져도 **목록에서 지우지 않는다**(§5-4). 표시만 흐려진다. */
    val active: Boolean = true,
    val needsRegeneration: Boolean = false,
)

/**
 * `POST /api/itineraries` 요청. **5-1 응답 구조 그대로**다. (API 명세 §5-2 🔒)
 *
 * 별도 DTO 를 만들지 않고 이름만 붙인다. 계약이 "요청 = 5-1 응답 구조(클라 편집 반영본)"
 * 이라, 두 벌로 두면 서버가 필드를 늘렸을 때 **한쪽만 따라가서 저장이 조용히 깨진다.**
 *
 * 보낸 `RACE` 블록의 제목·시간·장소·순서는 **서버가 믿지 않는다** — 저장 시점 canonical
 * 대회로 재구성해 강제 주입한다(§5-2). 그래도 구조상 함께 보낸다.
 */
typealias SaveItineraryRequestDto = GenerateItineraryResponse

/**
 * `POST /api/itineraries` 응답. (API 명세 §5-2 🔒)
 *
 * 같은 `(user, contestId, startDate, endDate)` 가 이미 있으면 **교체**하고
 * `200 {"id": 42, "replaced": true}` 가 온다. 처음 저장이면 `201 {"id": 42}` 라
 * [replaced] 가 없어서 `false` 로 떨어진다 — 화면이 "새로 저장" 과 "덮어썼다" 를 가른다.
 */
@Serializable
data class SaveItineraryResponseDto(val id: Long, val replaced: Boolean = false)

/**
 * `GET /api/itineraries/{id}` 응답 — 저장 동선 상세. (API 명세 §5-5)
 *
 * **5-1 응답 구조 + 저장 부가 정보**다. S7 복원·편집 모드 진입에 쓴다.
 *
 * **필수 필드에 기본값을 두지 않는다** (#202 리뷰). 서버가 항상 주는 값을 기본값으로
 * 메우면 빠진 응답이 정상 상세처럼 통과한다 — 화면만 이상하고 아무도 이유를 모른다.
 * 선택 필드는 `hotel`(숙소 없이 추천)과 `recovery`(하프·풀만)와 `region` 뿐이다.
 *
 * `days` 와 모든 블록은 **저장 시점 snapshot** 이다. 서버는 RACE 날짜·시간·장소를 최신
 * canonical 로 자동 덮어쓰지 않는다 — 달라진 것은 [contest] 로 따로 오고, 앱이 그것을
 * "대회 변경" 안내에 쓴다. 둘을 섞으면 사용자가 저장한 일정이 말없이 바뀐다.
 */
@Serializable
data class ItineraryDetailDto(
    val id: Long,
    val title: String,
    val contestId: Long,
    val event: String,
    val themes: List<String>,
    val startDate: String,
    val endDate: String,
    /** 숙소 없이 추천받은 동선은 없다. **선택 필드다** (§4.9). */
    val hotel: HotelDto? = null,
    /** 하프·풀만 온다. **선택 필드다** (§5.6-6). */
    val recovery: RecoveryDto? = null,
    val days: List<DayDto>,
    /** 저장 시점 snapshot 이다. 최신 대회의 지역이 아니다 (§5-5). */
    val region: String? = null,
    /** 저장 snapshot 과 현재 canonical 이 다르다. 화면은 "대회 변경" 배지를 띄운다 (§5-4). */
    val needsRegeneration: Boolean,
    /** **최신** canonical 대회. 위 snapshot 과 다를 수 있고, 그게 [needsRegeneration] 의 근거다. */
    val contest: ContestSnapshotDto,
)

/**
 * 상세가 함께 주는 최신 canonical 대회. (§5-5)
 *
 * **[active] 에 기본값을 두지 않는다** (#202 리뷰). 서버가 항상 주는 값이라, `true` 로
 * 메워지면 원천에서 사라진 대회(결정-53)가 살아 있는 것처럼 보인다.
 */
@Serializable
data class ContestSnapshotDto(
    val name: String,
    val region: String? = null,
    val place: String? = null,
    val contestDate: String? = null,
    val startTime: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val active: Boolean,
)

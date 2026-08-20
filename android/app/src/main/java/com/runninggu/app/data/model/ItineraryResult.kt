package com.runninggu.app.data.model

import com.runninggu.app.domain.ItineraryDay

/**
 * 서버가 만든 동선. (API 명세 §5-1 · SPEC 결정-41)
 *
 * **앱은 동선을 만들지 않는다.** 이 결과를 표시하고 저장 전 USER 블록만 편집한다.
 */
data class ItineraryResult(
    val title: String,
    /**
     * 서버가 돌려준 생성 조건 snapshot. (API 명세 §5-1 · §5-2)
     *
     * 저장 요청은 **요청값으로 다시 조립하지 않고 이 값을 쓴다** — 서버가 정규화했을 수
     * 있어서, 되돌려 보내는 쪽이 계약에 맞는다(#66 리뷰).
     */
    val request: ItineraryRequestSnapshot?,
    val days: List<ItineraryDay>,
    /** 하프·풀만 온다. 없으면 회복 배지를 그리지 않는다 (§5.6-6). */
    val recovery: RecoveryNote?,
    /** 일자별 회복일 플래그. 서버가 판정한 값을 그대로 들고 있는다. */
    val recoveryFlags: List<Boolean>,
)

/** 회복 안내 문구. 서버가 §5.1 표에서 만들어 준다. */
data class RecoveryNote(val label: String, val note: String)

/** 생성 응답이 함께 준 조건. 저장(§5-2)·재생성에 그대로 실어 보낸다. */
data class ItineraryRequestSnapshot(
    val contestId: Long?,
    val event: String,
    val themes: List<String>,
    val startDate: String?,
    val endDate: String?,
    val hotel: HotelSnapshot?,
)

data class HotelSnapshot(val name: String, val lat: Double, val lng: Double)

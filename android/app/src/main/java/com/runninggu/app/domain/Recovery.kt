package com.runninggu.app.domain

import kotlin.math.min

/**
 * 종목별 회복 룰. (SPEC §5.1 — 값 변경 금지)
 *
 * @param walkKm  회복일 권장 도보 거리 상한(km). 동선 산책 블록이 빠지면서
 *                **S7→S8 연계 시 목표 거리 기본값**으로 용도가 바뀌었다.
 * @param noHard  고강도 일정을 빼는 종목인지. 이 값이 true 일 때만 회복 배지가 뜬다.
 */
data class RecoveryRule(
    val walkKm: Int,
    val noHard: Boolean,
    val intensity: String,
    val dday: String,
    val dplus: String,
)

/** 회복 룰 표. (SPEC §5.1) */
object Recovery {

    private val RULES: Map<EventType, RecoveryRule> = mapOf(
        EventType.FIVE_K to RecoveryRule(
            walkKm = 8, noHard = false, intensity = "거의 정상",
            dday = "완주 후 오후부터 자유 관광", dplus = "일반 관광 자유",
        ),
        EventType.TEN_K to RecoveryRule(
            walkKm = 8, noHard = false, intensity = "낮은 피로",
            dday = "완주 후 가벼운 관광·축제", dplus = "일반 관광",
        ),
        EventType.HALF to RecoveryRule(
            walkKm = 5, noHard = true, intensity = "중등도 피로",
            dday = "완주 후 온천·휴식 권장", dplus = "온천+짧은 산책(고강도 제외)",
        ),
        EventType.FULL to RecoveryRule(
            walkKm = 3, noHard = true, intensity = "고강도 회복 필요",
            dday = "완주 후 회복 집중, 도보 최소", dplus = "스파·온천 중심, 도보 최소",
        ),
    )

    operator fun get(event: EventType): RecoveryRule = RULES.getValue(event)

    /**
     * 동선 결과에서 러닝코스로 넘어갈 때 채워 넣을 목표 거리(km). (SPEC §5.1)
     *
     * `min(walk, 5)` — 풀은 3km, 나머지는 5km 가 된다.
     * 원본 웹 구현에는 없던 규칙이라 포팅하며 새로 만든다.
     */
    fun defaultCourseTargetKm(event: EventType): Double = min(this[event].walkKm, 5).toDouble()
}

/** 회복 배지. 노출 조건과 문구가 종목에 매여 있다. (SPEC §5.6-6) */
data class RecoveryBadge(val label: String, val text: String, val intensity: String)

/**
 * 회복 배지를 만든다. (SPEC §5.6-6)
 *
 * `noHard` 종목(하프·풀)이 아니면 배지가 없다. D+ 일자가 있으면 그 라벨을,
 * 없으면 "D-day" 를 쓴다.
 *
 * @param dayOffsets 동선에 포함된 일자들의 대회일 기준 오프셋. 예) 2박 3일이면 [-1, 0, 1]
 */
fun recoveryBadgeOf(event: EventType, dayOffsets: List<Int>): RecoveryBadge? {
    val rule = Recovery[event]
    if (!rule.noHard) return null
    val plus = dayOffsets.filter { it > 0 }.minOrNull()
    return if (plus != null) {
        RecoveryBadge("${offLabel(plus)} 회복 모드", rule.dplus, rule.intensity)
    } else {
        RecoveryBadge("D-day 회복 모드", rule.dday, rule.intensity)
    }
}

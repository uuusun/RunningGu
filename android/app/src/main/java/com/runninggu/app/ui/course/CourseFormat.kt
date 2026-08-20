package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyItem
import java.util.Locale

/**
 * S8 카드 문구. (SPEC §4.11-5 · §4.11-b)
 *
 * Composable 밖에 두어 단위 테스트가 된다 — 문구는 명세에 못 박힌 계약이라 눈으로만
 * 확인하면 조용히 어긋난다.
 */

/** 슬라이더 사이 눈금 수. 1~21km 를 0.5 단위로 나눈 뒤 양 끝을 뺀다. */
internal val TARGET_SLIDER_STEPS: Int =
    ((CourseTargetKm.MAX - CourseTargetKm.MIN) / CourseTargetKm.STEP)
        .toInt() - 1

/** 5.0 → "5", 5.5 → "5.5". 정수는 소수점을 떼서 읽기 편하게 한다. */
fun formatKm(km: Double): String =
    if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()

/**
 * 내 주변 카드 부제.
 *
 * - 경로 있음 — "{왕복 km}·약 {분}분·난이도·상승 {m}m"
 * - 경로 없음 — "{카테고리} · {거리}"
 */
fun nearbySubtitle(item: NearbyItem): String = when (item) {
    is NearbyItem.Route -> buildList {
        add("${formatKm(item.routeKm)}km")
        add("약 ${item.durationMin}분")
        item.difficulty?.let { add(it.label) }
        add("상승 ${item.gainM}m")
    }.joinToString(" · ")

    is NearbyItem.Place -> buildList {
        item.category?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(formatDistance(item.distanceM))
    }.joinToString(" · ")
}

/** 지역별 카드 부제 — "{시군}·{km}·{난이도}·약 {분}분". (§4.11-b) */
fun courseSubtitle(course: CourseSummary): String = buildList {
    course.sigun?.takeIf { it.isNotBlank() }?.let { add(it) }
    add("${formatKm(course.distanceKm)}km")
    course.difficulty?.let { add(it.label) }
    course.durationMin?.let { add("약 ${it}분") }
}.joinToString(" · ")

/** 1km 미만은 m, 그 이상은 소수 한 자리 km. */
fun formatDistance(meters: Int): String =
    // Locale 을 안 주면 기기 설정에 따라 소수점이 "," 로 나온다
    if (meters < 1000) "${meters}m" else String.format(Locale.KOREA, "%.1fkm", meters / 1000.0)

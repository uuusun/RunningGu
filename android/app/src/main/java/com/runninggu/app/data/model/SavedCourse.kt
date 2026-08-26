package com.runninggu.app.data.model

import com.runninggu.app.domain.LatLng
import java.time.LocalDate

/**
 * 마이 `[러닝코스]` 목록 항목. (API 명세 §7-A · SPEC §4.13)
 *
 * 목록에는 **경로가 없다** — 상세에서만 온다(§7-A `pathPolyline` 제외 프로젝션).
 * 그래서 목록 카드는 지도를 그리지 않는다.
 */
data class SavedCourse(
    val id: Long,
    val courseName: String,
    val distanceKm: Double,
    val durationMin: Int,
    val gainM: Int,
    val difficulty: Difficulty?,
    val dataSource: CourseDataSource?,
    val region: String?,
    /** 저장한 날. KST 로 접어 카드에 "MM.DD 저장" 으로 쓴다. */
    /** 저장한 날(KST). 서버가 UTC `Z` 로 주고 매퍼가 접는다. */
    val savedAt: LocalDate,
)

/**
 * 저장 코스 상세. 경로 점선과 출처 한 줄을 그린다. (§7-A · 결정-44)
 *
 * [attributions] 는 **저장 시점 snapshot** 이라 외부 문구가 바뀌어도 그대로다. 앱은 순서를
 * 지켜 `" · "` 로 이어 붙이고 문구를 변형하지 않는다.
 */
data class SavedCourseDetail(
    val course: SavedCourse,
    val elevationProfileM: List<Int>,
    /**
     * 인코딩된 경로 **원문**. 서버에 되돌려 보낼 일이 있으면 이 값을 그대로 쓴다 —
     * 풀었다 다시 묶으면 `routeFingerprint` 가 달라진다(§7-A · [NearbyItem.Route] 와 같은 이유).
     *
     * **상세에는 항상 온다**(§7-A). 없는 응답은 계약 위반이라 해석 단계에서 걸린다(#209 리뷰).
     */
    val pathPolyline: String,
    /**
     * 지도에 그릴 좌표열. `data/remote` 매퍼가 [pathPolyline] 을 풀어 채운다 (AGENTS 2장-4 · #129).
     *
     * **원문이 필수여도 이건 비어 있을 수 있다.** 디코더는 깨진 입력에 예외를 던지지 않고
     * 읽은 만큼만 돌려준다 — 코스 상세가 통째로 안 열리는 것보다 낫기 때문이다(NFR-1·3).
     * 그래서 화면은 "필드가 없다" 가 아니라 **"풀었더니 점이 2개가 안 된다"** 를 본다.
     */
    val path: List<LatLng> = emptyList(),
    val attributions: List<String>,
)

/**
 * 저장 결과. (§7-A)
 *
 * [created] 가 false 면 같은 경로를 이미 저장해 둔 것이다 — 서버가 새 행을 만들지 않고
 * 기존 id 를 준다. 화면은 "저장했어요" 와 "이미 저장한 코스예요" 를 이 값으로 가른다.
 */
data class SaveCourseResult(val id: Long, val created: Boolean)

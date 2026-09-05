package com.runninggu.app.data.model

import com.runninggu.app.domain.LatLng
import java.time.Instant

/**
 * 큐레이션 코스 하나의 상세. (`GET /api/courses/{courseId}` · 이슈 #280)
 *
 * ## 왜 목록과 따로 있나
 *
 * 지역별 목록(`GET /api/courses` · §6-2)은 **좌표를 주지 않는다.** 그래서 코스를 눌러도
 * 앱이 그 코스가 어디인지 알 방법이 없었고, 목록 행에 `onClick` 자체가 없었다 —
 * "전국 코스 261" 을 보여주면서 아무것도 못 누르는 화면이었다(#280 관찰).
 *
 * ## `NearbyItem.Route` 와 무엇이 다른가
 *
 * `near` 의 경로는 **목표 거리에 맞춰 잘라 만든 왕복**이고, 이것은 **원본 코스 전체**다.
 * 같은 `courseId` 라도 두 응답의 거리·시간·고도가 다른 것이 정상이다(§4.11-b).
 *
 * - `distanceKm` — 원본 전체 길이
 * - `pathPolyline` — 원본 전체 points 순서의 E5 (잘린 구간이 아니다)
 * - `difficulty` — 전체 코스 등급. `near` 의 구간 등급과 달라도 정상이고 `HARD` 도 온다
 */
data class CuratedCourseDetail(
    val courseId: String,
    val courseName: String,
    val sido: String?,
    val sigun: String?,
    val distanceKm: Double,
    val difficulty: Difficulty?,
    val gainM: Int?,
    /** `distanceKm × 1000 / 110`, 최소 1분. 서버가 계산해 준다 (#280 계약). */
    val durationMin: Int?,
    val dataSource: CourseDataSource?,
    /** `API_GPX` 만 값이 있다. 번들 fallback 과 `GPX_ONLY` 는 null (§6-2 와 같은 규칙). */
    val syncedAt: Instant?,
    /** 원본 전체 points 의 2D Google Encoded Polyline E5. (결정-33 과 같은 인코딩) */
    val pathPolyline: String,
    /**
     * 지도에 그릴 좌표열. 매퍼가 [pathPolyline] 을 풀어 채운다 (AGENTS 2장-4).
     *
     * **원문이 있어도 비어 있을 수 있다** — 디코더는 깨진 입력에 예외를 던지지 않고 읽은
     * 만큼만 돌려준다(#209 와 같은 판단). 화면은 "점이 2개가 안 된다" 를 본다.
     */
    val path: List<LatLng>,
    /** 정수 미터, 순서 보존, 최대 100개. 미보유면 빈 배열. */
    val elevationProfileM: List<Int>,
    /** 완성 문구 배열. 앱은 변형하지 않고 `" · "` 로 잇는다 (결정-44). */
    val attributions: List<String>,
)

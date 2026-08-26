package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SaveCourseResult
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.model.SavedCourseDetail
import com.runninggu.app.data.remote.dto.SaveCourseRequestDto
import com.runninggu.app.data.remote.dto.SaveCourseResponseDto
import com.runninggu.app.data.remote.dto.SavedCourseDetailDto
import com.runninggu.app.data.remote.dto.SavedCourseDto

/**
 * 저장 코스 매핑. (API 명세 §7-A)
 *
 * 저장 요청은 **near 응답에서 그대로 만든다.** 화면이 값을 다시 조립하면 서버가 준 것과
 * 미세하게 달라져 중복 판정(fingerprint)이 흔들린다(이슈 #62).
 */
fun NearbyItem.Route.toSaveRequest(): SaveCourseRequestDto? {
    // 경로가 없으면 저장할 geometry 가 없다 — fingerprint 를 만들 수 없다
    val polyline = pathPolyline ?: return null
    return SaveCourseRequestDto(
        dataSource = dataSource?.name ?: return null,
        courseName = name,
        distanceKm = routeKm,
        durationMin = durationMin,
        gainM = gainM,
        elevationProfileM = elevationProfileM,
        entryLat = lat,
        entryLng = lng,
        // 서버가 준 문자열 그대로 — 디코딩해 다시 인코딩하지 않는다(이슈 #62)
        pathPolyline = polyline,
        difficulty = difficulty?.name,
        // 큐레이션 경로에만 있다. OSM 생성 경로는 서버가 만든 이름을 그대로 저장한다
        sourceCourseId = sourceCourseId,
        region = sido,
    )
}

fun SaveCourseResponseDto.toDomain(): SaveCourseResult = SaveCourseResult(id = id, created = created)

fun SavedCourseDto.toDomain(): SavedCourse = SavedCourse(
    id = id,
    courseName = courseName,
    distanceKm = distanceKm,
    durationMin = durationMin,
    gainM = gainM,
    difficulty = difficultyOf(difficulty),
    dataSource = dataSourceOf(dataSource),
    region = region,
    savedAt = toKstDate(savedAt),
)

fun SavedCourseDetailDto.toDomain(): SavedCourseDetail = SavedCourseDetail(
    course = SavedCourse(
        id = id,
        courseName = courseName,
        distanceKm = distanceKm,
        durationMin = durationMin,
        gainM = gainM,
        difficulty = difficultyOf(difficulty),
        dataSource = dataSourceOf(dataSource),
        region = region,
        savedAt = toKstDate(savedAt),
    ),
    elevationProfileM = elevationProfileM,
    pathPolyline = pathPolyline,
    // 와이어 형식을 푸는 것은 매퍼의 일이다 (AGENTS 2장-4 · #129).
    // 원문은 필수지만 결과는 빌 수 있다 — 디코더가 깨진 입력에 읽은 만큼만 돌려준다
    path = Polyline.decode(pathPolyline),
    attributions = attributions,
)

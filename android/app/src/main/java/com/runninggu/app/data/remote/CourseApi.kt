package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.CoursePageDto
import com.runninggu.app.data.remote.dto.CourseRegionsDto
import com.runninggu.app.data.remote.dto.CoursesNearDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 러닝코스 API. (API 명세 §6 · 공개)
 *
 * **앱은 `/courses/near` 를 한 번만 부른다**(결정-27). 서버가 경로와 걷기 스팟을 합쳐서
 * 거리순으로 준다 — 앱이 두 API 를 따로 부르고 섞으면 순서가 튄다.
 *
 * GraphHopper·카카오는 서버 내부 원천이라 앱 계약에 없다.
 */
interface CourseApi {

    /**
     * 출발지 주변 경로·장소 통합 목록. (§6-1)
     *
     * @param targetKm 1~21, 0.5 단위
     * @param radiusKm 큐레이션 진입점 조회 반경. 기본 8
     * @param size 경로+장소 합친 최대 항목 수. 기본·최대 12
     *
     * P0 에는 난이도 파라미터가 없다 — 서버가 `HARD` 를 자동 추천에서 제외한다(결정-42 개정).
     */
    @GET("courses/near")
    suspend fun near(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("targetKm") targetKm: Double,
        @Query("radiusKm") radiusKm: Double? = null,
        @Query("size") size: Int? = null,
    ): CoursesNearDto

    /** 지역별 목록. 큐레이션만 나온다. 거리 오름차순. (§6-2) */
    @GET("courses")
    suspend fun byRegion(
        @Query("region") region: String? = null,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
    ): CoursePageDto

    /** 지역 칩. 코스 수 내림차순. (§6-3) */
    @GET("courses/regions")
    suspend fun regions(): CourseRegionsDto

    companion object {
        /** 출발지 주변 기본·최대 항목 수 🔒(§6-1). */
        const val NEAR_SIZE = 12

        /** 큐레이션 진입점 조회 반경(km) 🔒(SPEC §5.8). */
        const val NEAR_RADIUS_KM = 8.0
    }
}

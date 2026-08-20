package com.runninggu.app.data.repository

import com.runninggu.app.data.model.GeocodedPlace
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException

/**
 * 백엔드 `/api/geocode` 가 준비되기 전까지 쓰는 스텁. (AP-12 · 매핑표 §6)
 *
 * 프리셋 5곳과 몇 군데만 아는 척한다. **모르는 말을 넣으면 서버처럼 `404 NO_RESULT` 를 던진다** —
 * 화면의 "못 찾았어요" 경로를 스텁으로도 볼 수 있어야 하기 때문이다.
 */
object FakeGeocodeRepository : GeocodeRepository {

    private val KNOWN = listOf(
        GeocodedPlace("해운대해수욕장", "부산 해운대구 우동", 35.1587, 129.1604),
        GeocodedPlace("여수엑스포역", "전남 여수시 덕충동", 34.7604, 127.6622),
        GeocodedPlace("강릉역", "강원 강릉시 교동", 37.7519, 128.8761),
        GeocodedPlace("강화터미널", "인천 강화군 강화읍", 37.7469, 126.4878),
        GeocodedPlace("서울시청", "서울 중구 세종대로 110", 37.5665, 126.9780),
        GeocodedPlace("여의도한강공원", "서울 영등포구 여의동로 330", 37.5285, 126.9327),
        GeocodedPlace("올림픽공원", "서울 송파구 올림픽로 424", 37.5202, 127.1215),
    )

    override suspend fun search(query: String): GeocodedPlace {
        val q = query.trim()
        return KNOWN.firstOrNull { it.name.contains(q) || it.address.contains(q) }
            ?: throw ApiException.Http(
                status = 404,
                code = ApiErrorCode.NO_RESULT,
                problem = null,
            )
    }
}

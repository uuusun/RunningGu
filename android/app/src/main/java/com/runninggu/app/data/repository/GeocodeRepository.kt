package com.runninggu.app.data.repository

import com.runninggu.app.data.model.GeocodedPlace
import com.runninggu.app.data.remote.GeocodeApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toDomain

/**
 * 출발지 검색 창구. (API 명세 §4-4 · SPEC §4.11-1)
 *
 * 결과가 없을 때 `null` 이 아니라 예외로 올라온다 — 서버가 `404 NO_RESULT` 를 주기 때문이다.
 * 화면은 그 코드로 "그런 장소를 못 찾았어요" 를 구분해 낸다.
 */
interface GeocodeRepository {
    suspend fun search(query: String): GeocodedPlace
}

/** 서버 구현. */
class RemoteGeocodeRepository(private val api: GeocodeApi) : GeocodeRepository {
    override suspend fun search(query: String): GeocodedPlace =
        apiCall { api.search(query.trim()).toDomain() }
}

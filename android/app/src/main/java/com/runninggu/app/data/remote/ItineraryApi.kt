package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.GenerateItineraryRequestDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 동선 API. (API 명세 §5 · 생성은 공개)
 *
 * **생성은 서버 단일 주체다**(SPEC 결정-41). 앱은 카테고리별 POI 를 모아 자체 엔진으로
 * 동선을 조립하지 않는다 — 이 응답을 표시하고 저장 전 USER 블록만 편집한다.
 */
interface ItineraryApi {

    /**
     * 동선 생성. **무상태 · 게스트 허용**. (§5-1)
     *
     * 정상인데 표시할 블록이 없으면 `200` 에 `days: []` 가 온다 — 화면은 Empty 다.
     * 네트워크·4xx·5xx 는 Error 이고 Empty 로 강등하지 않는다.
     */
    @POST("itineraries/generate")
    suspend fun generate(@Body body: GenerateItineraryRequestDto): GenerateItineraryResponse
}

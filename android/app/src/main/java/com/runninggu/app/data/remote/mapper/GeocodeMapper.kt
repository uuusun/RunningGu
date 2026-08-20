package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.GeocodedPlace
import com.runninggu.app.data.remote.dto.GeocodeDto

/** 출발지 검색 결과 → 앱 모델. (API 명세 §4-4) */
fun GeocodeDto.toDomain(): GeocodedPlace = GeocodedPlace(
    // 카카오가 이름을 안 주는 주소 검색도 있다 — 그때는 주소를 이름으로 쓴다
    name = name.ifBlank { address },
    address = address,
    lat = lat,
    lng = lng,
)

package com.runninggu.server.geocode.domain;

import java.math.BigDecimal;

/** 카카오 키워드 검색 첫 결과를 앱 좌표 순서로 바꾼 값이다. (API 명세 §4-4) */
public record GeocodeResult(
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng) {}

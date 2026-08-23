package com.runninggu.server.itinerary.domain;

import java.math.BigDecimal;
import java.time.LocalTime;

/** 사용자 블록 추가·수정에 공통으로 쓰는 검증 완료 값이다. */
public record ItineraryBlockDraft(
        LocalTime startTime,
        String title,
        BlockCategory category,
        String placeName,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String description) {}

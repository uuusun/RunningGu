package com.runninggu.server.contest.application;

import java.math.BigDecimal;

/** 목록 카드에 상세 전용 canonical 필드를 더한 조회 결과다. (API 명세 §3-4) */
public record ContestDetailItem(
        ContestListItem contest,
        String organizer,
        String officialUrl,
        BigDecimal lat,
        BigDecimal lng,
        int dDay) {}

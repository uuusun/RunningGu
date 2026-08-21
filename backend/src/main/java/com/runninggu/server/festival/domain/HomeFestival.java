package com.runninggu.server.festival.domain;

import java.time.LocalDate;

/** 홈에 표시할 전국 월간 축제 한 건이다. (SPEC §4.4, API 명세 §4-1) */
public record HomeFestival(
        String contentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String region,
        String imageUrl,
        boolean inProgress) {}

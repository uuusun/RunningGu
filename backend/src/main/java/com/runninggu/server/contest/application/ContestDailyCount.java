package com.runninggu.server.contest.application;

import java.time.LocalDate;

/** 캘린더 월간 뷰에 표시할 활성 대회 일별 건수다. (API 명세 §3-2) */
public record ContestDailyCount(LocalDate date, long count) {}

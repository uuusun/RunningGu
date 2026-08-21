package com.runninggu.server.contest.application;

/** 홈 마감 임박 카드와 KST 기준 접수 마감 잔여 일수다. (API 명세 §3-3) */
public record ContestClosingSoonItem(ContestListItem contest, int dDayApply) {}

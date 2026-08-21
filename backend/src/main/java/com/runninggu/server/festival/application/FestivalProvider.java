package com.runninggu.server.festival.application;

import com.runninggu.server.festival.domain.Festival;
import java.time.LocalDate;
import java.util.List;

/** 인근 축제 서비스가 한국관광공사 HTTP 계약을 직접 알지 않도록 분리한 조회 경계다. */
public interface FestivalProvider {

    List<Festival> searchStartingFrom(LocalDate eventStartDate);
}

package com.runninggu.server.festival.application;

import com.runninggu.server.festival.domain.Festival;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 축제 서비스가 한국관광공사 HTTP 계약을 직접 알지 않도록 분리한 조회 경계다. */
public interface FestivalProvider {

    List<Festival> searchStartingFrom(LocalDate eventStartDate);

    /**
     * 축제 공식 페이지 주소. (API 명세 §3-5·§4-1 `officialUrl`)
     *
     * 검색 응답에는 없는 값이라 건별로 따로 조회한다. KTO 가 홈페이지를 등록하지 않은 축제는
     * 비어 있고, 조회 자체가 실패하면 {@link FestivalProviderException} 을 던진다.
     */
    Optional<String> findOfficialUrl(String contentId);
}

package com.runninggu.server.geocode.application;

import com.runninggu.server.geocode.domain.GeocodeResult;
import java.util.Optional;

/** 외부 장소 검색의 입력·출력을 애플리케이션 계층에 고정한다. */
public interface GeocodeProvider {

    Optional<GeocodeResult> findFirst(String query);
}

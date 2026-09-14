package com.runninggu.server.festival.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 목록 응답에 붙일 축제 공식 페이지 주소. (API 명세 §3-5·§4-1 `officialUrl`)
 *
 * **링크 하나 때문에 목록을 실패시키지 않는다.** 조회가 실패하면 그 축제만 `officialUrl` 없이
 * 내려간다. 캐시는 {@link CachedFestivalOfficialUrlQuery} 에 있다 — 한 클래스 안에서 자기
 * 메서드를 부르면 캐시 프록시를 타지 않아 둘로 나눴다.
 */
@Service
public class FestivalOfficialUrlQuery {

    private static final Logger log = LoggerFactory.getLogger(FestivalOfficialUrlQuery.class);

    private final CachedFestivalOfficialUrlQuery cachedQuery;

    public FestivalOfficialUrlQuery(CachedFestivalOfficialUrlQuery cachedQuery) {
        this.cachedQuery = cachedQuery;
    }

    /** 없거나 못 가져오면 `null`. */
    public String findOrNull(String contentId) {
        try {
            return cachedQuery.find(contentId).url();
        } catch (FestivalProviderException exception) {
            log.warn("축제 공식 페이지 조회에 실패했습니다. reason={}", exception.reason());
            return null;
        }
    }
}

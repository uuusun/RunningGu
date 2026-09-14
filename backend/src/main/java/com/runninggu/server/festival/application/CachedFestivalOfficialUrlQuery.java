package com.runninggu.server.festival.application;

import com.runninggu.server.common.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 축제 공식 페이지 주소를 contentId 별로 하루 캐시한다. (API 명세 §3-5·§4-1 `officialUrl`)
 *
 * KTO `detailCommon2` 는 축제 한 건당 한 번 부른다. 홈 목록 캐시(5분)에 묶어 두면 5분마다 최대
 * 20건씩 다시 나가 개발계정 쿼터(오퍼레이션당 일 1,000건 · SPEC §9.5)를 넘길 수 있어서 따로 뗐다.
 * 홈페이지 주소는 하루 안에 바뀔 일이 없다.
 *
 * 실패({@link FestivalProviderException})는 캐시되지 않으므로 다음 요청이 다시 시도한다.
 * 목록 응답에 실패를 번지지 않게 하는 일은 {@link FestivalOfficialUrlQuery} 가 맡는다.
 */
@Service
public class CachedFestivalOfficialUrlQuery {

    private final FestivalProvider festivalProvider;

    public CachedFestivalOfficialUrlQuery(FestivalProvider festivalProvider) {
        this.festivalProvider = festivalProvider;
    }

    @Cacheable(
            cacheNames = CacheConfig.FESTIVAL_OFFICIAL_URL_CACHE,
            key = "#contentId",
            sync = true)
    public OfficialUrl find(String contentId) {
        return new OfficialUrl(festivalProvider.findOfficialUrl(contentId).orElse(null));
    }

    /**
     * 홈페이지가 등록되지 않은 축제도 하루 동안 다시 묻지 않으려고 감싼다 — 캐시가 null 값을
     * 받지 않는다({@code allowNullValues=false}).
     */
    public record OfficialUrl(String url) {}
}

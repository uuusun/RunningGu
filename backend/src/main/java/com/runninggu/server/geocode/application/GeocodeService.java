package com.runninggu.server.geocode.application;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.geocode.domain.GeocodeResult;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class GeocodeService {

    private final GeocodeProvider provider;

    public GeocodeService(GeocodeProvider provider) {
        this.provider = provider;
    }

    /** 카카오 첫 결과를 반환하며 성공 응답만 5분간 캐시한다. (API 명세 §0-5·§4-4) */
    @Cacheable(
            cacheNames = CacheConfig.GEOCODE_CACHE,
            key = "#query == null ? '' : #query.strip()",
            sync = true)
    public GeocodeResult geocode(String query) {
        String normalizedQuery = normalize(query);
        try {
            return provider.findFirst(normalizedQuery)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.NO_RESULT,
                            "검색 결과가 없습니다."));
        } catch (GeocodeProviderException exception) {
            if (exception.reason() == GeocodeProviderException.Reason.TIMEOUT) {
                throw new ApiException(
                        ErrorCode.EXTERNAL_API_TIMEOUT,
                        "장소 검색 응답 시간이 초과됐습니다.");
            }
            throw new ApiException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "장소 검색을 완료하지 못했습니다.");
        }
    }

    private String normalize(String query) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "query 값은 비어 있을 수 없습니다.");
        }
        return normalized;
    }
}

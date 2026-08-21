package com.runninggu.server.poi.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PoiService {

    private static final BigDecimal MIN_LAT = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LNG = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);

    private final CachedPoiSearchService cachedSearchService;

    public PoiService(CachedPoiSearchService cachedSearchService) {
        this.cachedSearchService = cachedSearchService;
    }

    public PoiSearchResult search(
            PoiCategory category,
            BigDecimal lat,
            BigDecimal lng,
            int radius,
            String query,
            int size) {
        validate(category, lat, lng, radius, size);
        String normalizedQuery = query == null ? "" : query.strip();
        if (query != null && normalizedQuery.length() < 2) {
            throw validation("query 값은 공백 제거 후 2자 이상이어야 합니다.");
        }
        return cachedSearchService.search(new PoiSearchCriteria(
                category,
                lat,
                lng,
                radius,
                normalizedQuery,
                size));
    }

    private void validate(
            PoiCategory category,
            BigDecimal lat,
            BigDecimal lng,
            int radius,
            int size) {
        Objects.requireNonNull(category);
        if (lat == null || lat.compareTo(MIN_LAT) < 0 || lat.compareTo(MAX_LAT) > 0) {
            throw validation("lat 값은 -90 이상 90 이하여야 합니다.");
        }
        if (lng == null || lng.compareTo(MIN_LNG) < 0 || lng.compareTo(MAX_LNG) > 0) {
            throw validation("lng 값은 -180 이상 180 이하여야 합니다.");
        }
        if (radius < 1 || radius > 20_000) {
            throw validation("radius 값은 1 이상 20000 이하여야 합니다.");
        }
        if (size < 1 || size > 20) {
            throw validation("size 값은 1 이상 20 이하여야 합니다.");
        }
    }

    private ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}

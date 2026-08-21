package com.runninggu.server.poi.application;

import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;

public record PoiSearchCriteria(
        PoiCategory category,
        BigDecimal lat,
        BigDecimal lng,
        int radius,
        String query,
        int size) {

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    public String cacheKey() {
        return String.join(
                "|",
                category.name(),
                lat.stripTrailingZeros().toPlainString(),
                lng.stripTrailingZeros().toPlainString(),
                Integer.toString(radius),
                query,
                Integer.toString(size));
    }

    public PoiSearchCriteria withRadius(int newRadius) {
        return new PoiSearchCriteria(category, lat, lng, newRadius, query, size);
    }
}

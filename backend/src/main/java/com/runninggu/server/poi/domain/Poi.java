package com.runninggu.server.poi.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Poi(
        String name,
        PoiCategory category,
        PoiProvider provider,
        BigDecimal lat,
        BigDecimal lng,
        int distanceM,
        String description,
        String address,
        String url,
        String imageUrl) {

    public Poi {
        name = Objects.requireNonNull(name);
        category = Objects.requireNonNull(category);
        provider = Objects.requireNonNull(provider);
        lat = Objects.requireNonNull(lat);
        lng = Objects.requireNonNull(lng);
        if (distanceM < 0) {
            throw new IllegalArgumentException("distanceM은 0 이상이어야 합니다.");
        }
        description = Objects.requireNonNullElse(description, "");
        address = Objects.requireNonNullElse(address, "");
        url = Objects.requireNonNullElse(url, "");
        imageUrl = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.strip();
    }
}

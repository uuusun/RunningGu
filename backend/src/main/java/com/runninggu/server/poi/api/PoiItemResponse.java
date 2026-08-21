package com.runninggu.server.poi.api;

import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "실제 외부 원천이 표시된 주변 장소")
public record PoiItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PoiCategory category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PoiProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal lat,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal lng,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int distanceM,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String address,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String imageUrl) {

    public static PoiItemResponse from(Poi poi) {
        return new PoiItemResponse(
                poi.name(),
                poi.category(),
                poi.provider(),
                poi.lat(),
                poi.lng(),
                poi.distanceM(),
                poi.description(),
                poi.address(),
                poi.url(),
                poi.imageUrl());
    }
}

package com.runninggu.server.poi.api;

import com.runninggu.server.poi.application.PoiSearchResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PoiSearchResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        allowableValues = "LIVE")
                String source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PoiItemResponse> items) {

    private static final String LIVE = "LIVE";

    public static PoiSearchResponse from(PoiSearchResult result) {
        return new PoiSearchResponse(
                LIVE,
                result.items().stream().map(PoiItemResponse::from).toList());
    }
}

package com.runninggu.server.poi.api;

import com.runninggu.server.poi.application.PoiService;
import com.runninggu.server.poi.domain.PoiCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "POI", description = "위저드 숙소와 동선 장소 후보")
@RestController
@RequestMapping("/api/pois")
public class PoiController {

    private final PoiService poiService;

    public PoiController(PoiService poiService) {
        this.poiService = poiService;
    }

    @Operation(summary = "위저드 숙소 · 동선 슬롯 · 교체/추가 시트")
    @GetMapping
    public PoiSearchResponse search(
            @Parameter(required = true, example = "LODGING")
                    @RequestParam(name = "category")
                    PoiCategory category,
            @Parameter(
                            required = true,
                            example = "36.4912",
                            schema = @Schema(minimum = "-90", maximum = "90"))
                    @RequestParam(name = "lat")
                    BigDecimal lat,
            @Parameter(
                            required = true,
                            example = "127.2714",
                            schema = @Schema(minimum = "-180", maximum = "180"))
                    @RequestParam(name = "lng")
                    BigDecimal lng,
            @Parameter(
                            description = "검색 반경(m), 1~20000",
                            example = "8000",
                            schema = @Schema(minimum = "1", maximum = "20000"))
                    @RequestParam(name = "radius", defaultValue = "8000")
                    int radius,
            @Parameter(description = "공백 제거 후 2자 이상인 선택 검색어")
                    @RequestParam(name = "query", required = false)
                    String query,
            @Parameter(
                            description = "반환 개수, 1~20",
                            example = "8",
                            schema = @Schema(minimum = "1", maximum = "20"))
                    @RequestParam(name = "size", defaultValue = "8")
                    int size) {
        return PoiSearchResponse.from(
                poiService.search(category, lat, lng, radius, query, size));
    }
}

package com.runninggu.server.geocode.api;

import com.runninggu.server.geocode.application.GeocodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocode")
public class GeocodeController {

    private final GeocodeService geocodeService;

    public GeocodeController(GeocodeService geocodeService) {
        this.geocodeService = geocodeService;
    }

    @Operation(summary = "출발지 검색")
    @GetMapping
    public GeocodeResponse geocode(
            @Parameter(required = true, example = "해운대해수욕장")
                    @RequestParam(name = "query")
                    String query) {
        return GeocodeResponse.from(geocodeService.geocode(query));
    }
}

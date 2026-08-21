package com.runninggu.server.festival.api;

import com.runninggu.server.festival.application.NearbyFestivalService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contests/{contestId}/festivals")
public class NearbyFestivalController {

    private final NearbyFestivalService service;

    public NearbyFestivalController(NearbyFestivalService service) {
        this.service = service;
    }

    @Operation(summary = "대회 인근 축제 조회")
    @GetMapping
    public NearbyFestivalListResponse nearbyFestivals(@PathVariable long contestId) {
        return NearbyFestivalListResponse.from(service.findNearby(contestId));
    }
}

package com.runninggu.server.festival.api;

import com.runninggu.server.festival.application.HomeFestivalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/festivals")
public class HomeFestivalController {

    private final HomeFestivalService service;

    public HomeFestivalController(HomeFestivalService service) {
        this.service = service;
    }

    @Operation(summary = "홈 월간 축제 조회")
    @GetMapping
    public HomeFestivalListResponse festivals(
            @Parameter(example = "2026-08")
                    @RequestParam(required = false)
                    String yearMonth,
            @Parameter(example = "6")
                    @RequestParam(required = false)
                    Integer size) {
        return HomeFestivalListResponse.from(service.find(yearMonth, size));
    }
}

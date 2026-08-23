package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "동선", description = "대회 전후 여행 동선 생성·저장·편집")
@RestController
@RequestMapping("/api/itineraries")
public class ItineraryGenerationController {

    private final ItineraryGenerationService generationService;

    public ItineraryGenerationController(ItineraryGenerationService generationService) {
        this.generationService = generationService;
    }

    @Operation(summary = "동선 생성", description = "게스트 허용 무상태 생성")
    @PostMapping("/generate")
    public GenerateItineraryResponse generate(
            @Valid @RequestBody GenerateItineraryRequest request) {
        return GenerateItineraryResponse.from(generationService.generate(request.toCommand()));
    }
}

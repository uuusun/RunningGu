package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryBlockCommands.Add;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record AddItineraryBlockRequest(
        @Schema(pattern = "HH:mm", example = "13:00", defaultValue = "13:00") String startTime,
        @NotBlank String title,
        @NotBlank String category,
        String placeName,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String description) {

    public Add toCommand() {
        return new Add(
                startTime,
                title,
                category,
                placeName,
                address,
                lat,
                lng,
                description);
    }
}

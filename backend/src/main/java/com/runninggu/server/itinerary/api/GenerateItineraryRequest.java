package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.GenerateItineraryCommand;
import com.runninggu.server.itinerary.application.GenerateItineraryCommand.HotelInput;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** `POST /api/itineraries/generate` 요청 계약이다. (API 명세 §5-1) */
public record GenerateItineraryRequest(
        @NotNull @Positive @Schema(example = "153") Long contestId,
        @NotBlank @Schema(type = "string", format = "date", example = "2026-08-21")
                String startDate,
        @NotBlank @Schema(type = "string", format = "date", example = "2026-08-23")
                String endDate,
        @NotBlank
                @Schema(allowableValues = {"K5", "K10", "HALF", "FULL"}, example = "HALF")
                String event,
        @NotEmpty
                @ArraySchema(schema = @Schema(allowableValues = {
                    "TOUR", "FOOD", "CAFE", "WELLNESS", "NATURE", "HISTORY"
                }))
                List<@NotBlank String> themes,
        @Valid ItineraryHotelRequest hotel) {

    public GenerateItineraryCommand toCommand() {
        return new GenerateItineraryCommand(
                contestId,
                startDate,
                endDate,
                event,
                themes,
                hotel == null
                        ? null
                        : new HotelInput(hotel.name(), hotel.lat(), hotel.lng()));
    }
}

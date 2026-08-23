package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.PersistItineraryCommand;
import com.runninggu.server.itinerary.application.PersistItineraryCommand.BlockInput;
import com.runninggu.server.itinerary.application.PersistItineraryCommand.DayInput;
import com.runninggu.server.itinerary.application.PersistItineraryCommand.HotelInput;
import com.runninggu.server.itinerary.application.PersistItineraryCommand.RecoveryInput;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 생성 응답에서 USER 블록을 로컬 편집한 뒤 보내는 저장 snapshot 계약이다. */
public record SaveItineraryRequest(
        @NotBlank @Schema(example = "2박 3일") String title,
        @NotNull @Positive @Schema(example = "153") Long contestId,
        @NotBlank @Schema(allowableValues = {"K5", "K10", "HALF", "FULL"}) String event,
        @NotEmpty
                @ArraySchema(schema = @Schema(allowableValues = {
                    "TOUR", "FOOD", "CAFE", "WELLNESS", "NATURE", "HISTORY"
                }))
                List<@NotBlank String> themes,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Valid ItineraryHotelRequest hotel,
        @Valid RecoveryRequest recovery,
        @NotEmpty List<@Valid DayRequest> days) {

    public PersistItineraryCommand toCommand() {
        return new PersistItineraryCommand(
                contestId,
                event,
                themes,
                startDate,
                endDate,
                hotel == null ? null : new HotelInput(hotel.name(), hotel.lat(), hotel.lng()),
                recovery == null ? null : new RecoveryInput(recovery.label(), recovery.note()),
                days.stream().map(DayRequest::toCommand).toList());
    }

    public record RecoveryRequest(@NotBlank String label, @NotBlank String note) {}

    public record DayRequest(
            int dayIndex,
            @NotNull LocalDate date,
            boolean recovery,
            String note,
            @NotNull List<@Valid BlockRequest> blocks) {

        private DayInput toCommand() {
            return new DayInput(
                    dayIndex,
                    date,
                    recovery,
                    note,
                    blocks.stream().map(BlockRequest::toCommand).toList());
        }
    }

    public record BlockRequest(
            @NotBlank @Schema(pattern = "HH:mm", example = "15:00") String startTime,
            @NotBlank String title,
            @NotBlank String category,
            String placeName,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            String description,
            @NotBlank @Schema(allowableValues = {"USER", "RACE"}) String blockType,
            boolean systemManaged) {

        private BlockInput toCommand() {
            return new BlockInput(
                    startTime,
                    title,
                    category,
                    placeName,
                    address,
                    lat,
                    lng,
                    description,
                    blockType,
                    systemManaged);
        }
    }
}

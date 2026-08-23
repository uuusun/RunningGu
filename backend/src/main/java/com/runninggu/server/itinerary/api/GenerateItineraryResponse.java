package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.domain.GeneratedBlock;
import com.runninggu.server.itinerary.domain.GeneratedDay;
import com.runninggu.server.itinerary.domain.GeneratedItinerary;
import com.runninggu.server.itinerary.domain.GeneratedRecovery;
import com.runninggu.server.itinerary.domain.ItineraryHotel;
import com.runninggu.server.itinerary.domain.ItineraryPlace;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Android가 표시하고 저장 전 USER 블록만 편집하는 무상태 동선 DTO다. */
public record GenerateItineraryResponse(
        @Schema(example = "2박 3일") String title,
        long contestId,
        @Schema(allowableValues = {"K5", "K10", "HALF", "FULL"}) String event,
        List<String> themes,
        LocalDate startDate,
        LocalDate endDate,
        HotelResponse hotel,
        RecoveryResponse recovery,
        List<DayResponse> days) {

    public static GenerateItineraryResponse from(GeneratedItinerary generated) {
        var plan = generated.plan();
        return new GenerateItineraryResponse(
                generated.title(),
                plan.race().id(),
                plan.event().name(),
                plan.themes().stream().map(Enum::name).toList(),
                plan.startDate(),
                plan.endDate(),
                HotelResponse.from(plan.hotel()),
                RecoveryResponse.from(generated.recovery()),
                generated.days().stream().map(DayResponse::from).toList());
    }

    public record HotelResponse(String name, BigDecimal lat, BigDecimal lng) {

        private static HotelResponse from(ItineraryHotel hotel) {
            return hotel == null ? null : new HotelResponse(hotel.name(), hotel.lat(), hotel.lng());
        }
    }

    public record RecoveryResponse(String label, String note) {

        private static RecoveryResponse from(GeneratedRecovery recovery) {
            return recovery == null
                    ? null
                    : new RecoveryResponse(recovery.label(), recovery.note());
        }
    }

    public record DayResponse(
            @Schema(description = "대회일 기준 상대 오프셋", example = "-1") int dayIndex,
            LocalDate date,
            @Schema(example = "D-1") String dayLabel,
            boolean recovery,
            String note,
            List<BlockResponse> blocks) {

        private static DayResponse from(GeneratedDay day) {
            return new DayResponse(
                    day.dayIndex(),
                    day.date(),
                    day.dayLabel(),
                    day.recovery(),
                    day.note(),
                    day.blocks().stream().map(BlockResponse::from).toList());
        }
    }

    public record BlockResponse(
            @Schema(pattern = "HH:mm", example = "15:00") String startTime,
            String title,
            String category,
            String placeName,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            String description,
            @Schema(allowableValues = {"USER", "RACE"}) String blockType,
            boolean systemManaged) {

        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

        private static BlockResponse from(GeneratedBlock block) {
            ItineraryPlace place = block.place();
            return new BlockResponse(
                    block.startTime().format(TIME_FORMAT),
                    block.title(),
                    block.category().name(),
                    place == null ? null : place.name(),
                    place == null ? null : place.address(),
                    place == null ? null : place.lat(),
                    place == null ? null : place.lng(),
                    block.description(),
                    block.blockType().name(),
                    block.systemManaged());
        }
    }
}

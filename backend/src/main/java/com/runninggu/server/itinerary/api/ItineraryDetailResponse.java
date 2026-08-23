package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryViews.CurrentContest;
import com.runninggu.server.itinerary.application.ItineraryViews.Day;
import com.runninggu.server.itinerary.application.ItineraryViews.Details;
import com.runninggu.server.itinerary.application.ItineraryViews.Hotel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ItineraryDetailResponse(
        long id,
        String title,
        long contestId,
        String event,
        List<String> themes,
        LocalDate startDate,
        LocalDate endDate,
        HotelResponse hotel,
        ItineraryListResponse.RecoveryResponse recovery,
        String region,
        List<DayResponse> days,
        boolean needsRegeneration,
        ContestResponse contest) {

    public static ItineraryDetailResponse from(Details details) {
        return new ItineraryDetailResponse(
                details.id(),
                details.title(),
                details.contestId(),
                details.event(),
                details.themes(),
                details.startDate(),
                details.endDate(),
                HotelResponse.from(details.hotel()),
                ItineraryListResponse.RecoveryResponse.from(details.recovery()),
                details.region(),
                details.days().stream().map(DayResponse::from).toList(),
                details.needsRegeneration(),
                ContestResponse.from(details.contest()));
    }

    public record HotelResponse(String name, BigDecimal lat, BigDecimal lng) {

        private static HotelResponse from(Hotel hotel) {
            return hotel == null ? null : new HotelResponse(hotel.name(), hotel.lat(), hotel.lng());
        }
    }

    public record DayResponse(
            long id,
            int dayIndex,
            LocalDate date,
            String dayLabel,
            boolean recovery,
            String note,
            List<ItineraryBlockResponse> blocks) {

        private static DayResponse from(Day day) {
            return new DayResponse(
                    day.id(),
                    day.dayIndex(),
                    day.date(),
                    day.dayLabel(),
                    day.recovery(),
                    day.note(),
                    day.blocks().stream().map(ItineraryBlockResponse::from).toList());
        }
    }

    public record ContestResponse(
            String name,
            String region,
            String place,
            LocalDate contestDate,
            String startTime,
            BigDecimal lat,
            BigDecimal lng,
            boolean active) {

        private static ContestResponse from(CurrentContest contest) {
            return new ContestResponse(
                    contest.name(),
                    contest.region(),
                    contest.place(),
                    contest.contestDate(),
                    contest.startTime(),
                    contest.lat(),
                    contest.lng(),
                    contest.active());
        }
    }
}

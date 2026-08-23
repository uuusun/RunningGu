package com.runninggu.server.itinerary.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.itinerary.application.GenerateItineraryCommand.HotelInput;
import com.runninggu.server.itinerary.domain.GeneratedItinerary;
import com.runninggu.server.itinerary.domain.ItineraryGenerator;
import com.runninggu.server.itinerary.domain.ItineraryHotel;
import com.runninggu.server.itinerary.domain.ItineraryPlan;
import com.runninggu.server.itinerary.domain.ItineraryRace;
import com.runninggu.server.itinerary.domain.PoiPools;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** canonical 대회·POI 어댑터를 조율하고 순수 동선 엔진을 호출한다. */
@Service
public class ItineraryGenerationService {

    private static final int MAX_TRAVEL_DAYS = 7;
    private static final BigDecimal MIN_LAT = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LNG = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);

    private final ContestRepository contestRepository;
    private final ItineraryPoiPoolLoader poiPoolLoader;
    private final ItineraryGenerator generator;

    public ItineraryGenerationService(
            ContestRepository contestRepository,
            ItineraryPoiPoolLoader poiPoolLoader) {
        this.contestRepository = contestRepository;
        this.poiPoolLoader = poiPoolLoader;
        this.generator = new ItineraryGenerator();
    }

    public GeneratedItinerary generate(GenerateItineraryCommand command) {
        Contest contest = contestRepository.findById(command.contestId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONTEST_NOT_FOUND,
                        "대회 ID " + command.contestId() + "를 찾을 수 없습니다."));
        validateContest(contest);

        LocalDate startDate = parseDate(command.startDate(), "startDate");
        LocalDate endDate = parseDate(command.endDate(), "endDate");
        validatePeriod(startDate, endDate, contest.getContestDate());
        ContestEventType event = parseEvent(command.event());
        List<PoiCategory> themes = parseThemes(command.themes());
        ItineraryHotel hotel = toHotel(command.hotel());

        ItineraryPlan plan = new ItineraryPlan(
                new ItineraryRace(
                        contest.getId(),
                        contest.getName(),
                        contest.getPlace(),
                        contest.getRoadAddress(),
                        contest.getContestDate(),
                        contest.getStartTime(),
                        contest.getLat(),
                        contest.getLng()),
                hotel,
                event,
                themes,
                startDate,
                endDate);
        PoiPools pools = poiPoolLoader.load(
                generator.requiredCategories(plan),
                contest.getLat(),
                contest.getLng());
        return generator.generate(plan, pools);
    }

    private void validateContest(Contest contest) {
        if (!contest.isActive()) {
            throw new ApiException(
                    ErrorCode.CONTEST_INACTIVE,
                    "정보 제공이 종료된 대회로는 새 동선을 만들 수 없습니다.");
        }
        if (contest.getLat() == null || contest.getLng() == null) {
            throw new ApiException(
                    ErrorCode.CONTEST_LOCATION_UNAVAILABLE,
                    "대회장 좌표가 없어 동선을 만들 수 없습니다.");
        }
    }

    private LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeException | NullPointerException exception) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    field + " 값은 YYYY-MM-DD 형식이어야 합니다.");
        }
    }

    private void validatePeriod(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate contestDate) {
        long dayCount = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (endDate.isBefore(startDate)
                || dayCount > MAX_TRAVEL_DAYS
                || contestDate.isBefore(startDate)
                || contestDate.isAfter(endDate)) {
            throw new ApiException(
                    ErrorCode.INVALID_TRAVEL_PERIOD,
                    "여행 기간은 역순일 수 없고 최대 7일이며 대회일을 포함해야 합니다.");
        }
    }

    private ContestEventType parseEvent(String raw) {
        try {
            return ContestEventType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "event 값은 K5, K10, HALF, FULL 중 하나여야 합니다.");
        }
    }

    private List<PoiCategory> parseThemes(List<String> rawThemes) {
        if (rawThemes == null || rawThemes.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "themes는 한 개 이상이어야 합니다.");
        }
        List<PoiCategory> themes = new ArrayList<>();
        for (String raw : rawThemes) {
            PoiCategory category;
            try {
                category = PoiCategory.valueOf(raw);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "themes 값이 올바르지 않습니다.");
            }
            if (category == PoiCategory.LODGING) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "LODGING은 여행 취향으로 선택할 수 없습니다.");
            }
            themes.add(category);
        }
        return List.copyOf(themes);
    }

    private ItineraryHotel toHotel(HotelInput hotel) {
        if (hotel == null) {
            return null;
        }
        if (hotel.name() == null || hotel.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "hotel.name 값이 필요합니다.");
        }
        validateCoordinate(hotel.lat(), MIN_LAT, MAX_LAT, "hotel.lat");
        validateCoordinate(hotel.lng(), MIN_LNG, MAX_LNG, "hotel.lng");
        return new ItineraryHotel(hotel.name().strip(), hotel.lat(), hotel.lng());
    }

    private void validateCoordinate(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            String field) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    field + " 값이 올바르지 않습니다.");
        }
    }
}

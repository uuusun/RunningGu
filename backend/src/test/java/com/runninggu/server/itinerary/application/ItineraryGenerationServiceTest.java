package com.runninggu.server.itinerary.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.itinerary.application.GenerateItineraryCommand.HotelInput;
import com.runninggu.server.itinerary.domain.GeneratedItinerary;
import com.runninggu.server.itinerary.domain.PoiPools;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ItineraryGenerationServiceTest {

    private static final long CONTEST_ID = 153L;
    private static final LocalDate RACE_DATE = LocalDate.of(2026, 8, 22);
    private static final BigDecimal LAT = new BigDecimal("36.4912000");
    private static final BigDecimal LNG = new BigDecimal("127.2714000");

    private ContestRepository contestRepository;
    private ItineraryPoiPoolLoader poolLoader;
    private ItineraryGenerationService service;

    @BeforeEach
    void setUp() {
        contestRepository = mock(ContestRepository.class);
        poolLoader = mock(ItineraryPoiPoolLoader.class);
        service = new ItineraryGenerationService(contestRepository, poolLoader);
    }

    @Test
    void 대회가_없으면_CONTEST_NOT_FOUND다() {
        given(contestRepository.findById(CONTEST_ID)).willReturn(Optional.empty());

        assertError(command(), ErrorCode.CONTEST_NOT_FOUND);
        verify(poolLoader, never()).load(anyList(), eq(LAT), eq(LNG));
    }

    @Test
    void 비활성_대회는_CONTEST_INACTIVE다() {
        givenContest(contest(false, LAT, LNG));

        assertError(command(), ErrorCode.CONTEST_INACTIVE);
        verify(poolLoader, never()).load(anyList(), eq(LAT), eq(LNG));
    }

    @Test
    void 좌표가_없는_대회는_CONTEST_LOCATION_UNAVAILABLE다() {
        givenContest(contest(true, null, null));

        assertError(command(), ErrorCode.CONTEST_LOCATION_UNAVAILABLE);
    }

    @Test
    void 역순_칠일초과_대회일미포함은_INVALID_TRAVEL_PERIOD다() {
        givenContest(contest(true, LAT, LNG));

        assertError(command("2026-08-23", "2026-08-21"), ErrorCode.INVALID_TRAVEL_PERIOD);
        assertError(command("2026-08-16", "2026-08-23"), ErrorCode.INVALID_TRAVEL_PERIOD);
        assertError(command("2026-08-20", "2026-08-21"), ErrorCode.INVALID_TRAVEL_PERIOD);
    }

    @Test
    void 날짜형식_종목_취향_계약을_검증한다() {
        givenContest(contest(true, LAT, LNG));

        assertError(new GenerateItineraryCommand(
                        CONTEST_ID,
                        "2026-99-99",
                        "2026-08-23",
                        "HALF",
                        List.of("TOUR"),
                        null),
                ErrorCode.VALIDATION_FAILED);
        assertError(new GenerateItineraryCommand(
                        CONTEST_ID,
                        "2026-08-21",
                        "2026-08-23",
                        "TEN_K",
                        List.of("TOUR"),
                        null),
                ErrorCode.VALIDATION_FAILED);
        assertError(new GenerateItineraryCommand(
                        CONTEST_ID,
                        "2026-08-21",
                        "2026-08-23",
                        "HALF",
                        List.of(),
                        null),
                ErrorCode.VALIDATION_FAILED);
        assertError(new GenerateItineraryCommand(
                        CONTEST_ID,
                        "2026-08-21",
                        "2026-08-23",
                        "HALF",
                        List.of("LODGING"),
                        null),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 숙소_필드와_좌표를_검증한다() {
        givenContest(contest(true, LAT, LNG));

        assertError(command(new HotelInput(" ", LAT, LNG)), ErrorCode.VALIDATION_FAILED);
        assertError(
                command(new HotelInput("호텔", new BigDecimal("90.1"), LNG)),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 대회와_요청을_동선_계획으로_만들고_필요한_POI_풀을_조회한다() {
        givenContest(contest(true, LAT, LNG));
        given(poolLoader.load(anyList(), eq(LAT), eq(LNG)))
                .willReturn(new PoiPools(Map.of(), Map.of()));

        GeneratedItinerary generated = service.generate(command());

        assertThat(generated.title()).isEqualTo("2박 3일");
        assertThat(generated.plan().race().id()).isEqualTo(CONTEST_ID);
        assertThat(generated.plan().themes())
                .containsExactly(PoiCategory.TOUR, PoiCategory.FOOD);
        assertThat(generated.plan().hotel().name()).isEqualTo("호텔 세종 가온");
        assertThat(generated.days()).extracting(day -> day.dayIndex())
                .containsExactly(-1, 0, 1);
        verify(poolLoader).load(
                eq(List.of(PoiCategory.FOOD, PoiCategory.TOUR, PoiCategory.WELLNESS)),
                eq(LAT),
                eq(LNG));
    }

    private GenerateItineraryCommand command() {
        return command(new HotelInput("호텔 세종 가온", LAT, LNG));
    }

    private GenerateItineraryCommand command(HotelInput hotel) {
        return new GenerateItineraryCommand(
                CONTEST_ID,
                "2026-08-21",
                "2026-08-23",
                "HALF",
                List.of("TOUR", "FOOD"),
                hotel);
    }

    private GenerateItineraryCommand command(String startDate, String endDate) {
        return new GenerateItineraryCommand(
                CONTEST_ID,
                startDate,
                endDate,
                "HALF",
                List.of("TOUR", "FOOD"),
                null);
    }

    private void givenContest(Contest contest) {
        given(contestRepository.findById(CONTEST_ID)).willReturn(Optional.of(contest));
    }

    private Contest contest(boolean active, BigDecimal lat, BigDecimal lng) {
        Contest contest = mock(Contest.class);
        given(contest.getId()).willReturn(CONTEST_ID);
        given(contest.isActive()).willReturn(active);
        given(contest.getName()).willReturn("세종 러닝 페스티벌");
        given(contest.getPlace()).willReturn("세종호수공원");
        given(contest.getRoadAddress()).willReturn("세종특별자치시 다솜로 216");
        given(contest.getContestDate()).willReturn(RACE_DATE);
        given(contest.getStartTime()).willReturn(LocalTime.of(9, 0));
        given(contest.getLat()).willReturn(lat);
        given(contest.getLng()).willReturn(lng);
        return contest;
    }

    private void assertError(GenerateItineraryCommand command, ErrorCode expected) {
        assertThatThrownBy(() -> service.generate(command))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}

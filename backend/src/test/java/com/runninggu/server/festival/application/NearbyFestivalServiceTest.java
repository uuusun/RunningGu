package com.runninggu.server.festival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestCategory;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import com.runninggu.server.festival.domain.NearbyFestival;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NearbyFestivalServiceTest {

    private static final long CONTEST_ID = 1L;
    private static final LocalDate CONTEST_DATE = LocalDate.of(2026, 8, 21);
    private static final BigDecimal CONTEST_LAT = new BigDecimal("37.0000000");
    private static final BigDecimal CONTEST_LNG = new BigDecimal("127.0000000");

    @Mock
    private ContestRepository contestRepository;

    @Mock
    private FestivalProvider festivalProvider;

    private NearbyFestivalService service;

    @BeforeEach
    void setUp() {
        service = new NearbyFestivalService(contestRepository, festivalProvider);
    }

    @Test
    void 날짜_경계와_반경_40km를_포함해_필터한다() {
        Contest contest = contest(CONTEST_LAT, CONTEST_LNG);
        LocalDate windowStart = CONTEST_DATE.minusDays(14);
        LocalDate windowEnd = CONTEST_DATE.plusDays(14);
        given(contestRepository.findById(CONTEST_ID)).willReturn(Optional.of(contest));
        given(festivalProvider.searchStartingFrom(windowStart)).willReturn(List.of(
                festival("start-boundary", windowEnd, windowEnd, "37.0100000"),
                festival("end-boundary", windowStart.minusDays(2), windowStart, "37.0200000"),
                festival("too-early", windowStart.minusDays(2), windowStart.minusDays(1), "37.0100000"),
                festival("too-late", windowEnd.plusDays(1), windowEnd.plusDays(2), "37.0100000"),
                festival("too-far", windowStart, windowEnd, "37.3700000")));

        List<NearbyFestival> result = service.findNearby(CONTEST_ID);

        assertThat(result)
                .extracting(NearbyFestival::contentId)
                .containsExactly("start-boundary", "end-boundary");
    }

    @Test
    void 거리순으로_정렬하고_여섯_건까지만_반환한다() {
        Contest contest = contest(CONTEST_LAT, CONTEST_LNG);
        LocalDate windowStart = CONTEST_DATE.minusDays(14);
        given(contestRepository.findById(CONTEST_ID)).willReturn(Optional.of(contest));
        List<Festival> festivals = IntStream.rangeClosed(1, 7)
                .mapToObj(index -> festival(
                        "festival-" + index,
                        CONTEST_DATE,
                        CONTEST_DATE,
                        "37.0" + index + "00000"))
                .toList()
                .reversed();
        given(festivalProvider.searchStartingFrom(windowStart)).willReturn(festivals);

        List<NearbyFestival> result = service.findNearby(CONTEST_ID);

        assertThat(result).hasSize(6);
        assertThat(result)
                .extracting(NearbyFestival::contentId)
                .containsExactly(
                        "festival-1",
                        "festival-2",
                        "festival-3",
                        "festival-4",
                        "festival-5",
                        "festival-6");
    }

    @Test
    void 대회가_없거나_좌표가_없으면_외부_API를_호출하지_않는다() {
        given(contestRepository.findById(404L)).willReturn(Optional.empty());
        assertErrorCode(() -> service.findNearby(404L), ErrorCode.CONTEST_NOT_FOUND);

        given(contestRepository.findById(CONTEST_ID)).willReturn(Optional.of(contest(null, null)));
        assertErrorCode(
                () -> service.findNearby(CONTEST_ID),
                ErrorCode.CONTEST_LOCATION_UNAVAILABLE);

        verifyNoInteractions(festivalProvider);
    }

    @Test
    void 외부_일반오류와_타임아웃을_구분한다() {
        Contest contest = contest(CONTEST_LAT, CONTEST_LNG);
        LocalDate windowStart = CONTEST_DATE.minusDays(14);
        given(contestRepository.findById(CONTEST_ID)).willReturn(Optional.of(contest));
        given(festivalProvider.searchStartingFrom(windowStart))
                .willThrow(new FestivalProviderException(Reason.ERROR))
                .willThrow(new FestivalProviderException(Reason.TIMEOUT));

        assertErrorCode(
                () -> service.findNearby(CONTEST_ID),
                ErrorCode.EXTERNAL_API_ERROR);
        assertErrorCode(
                () -> service.findNearby(CONTEST_ID),
                ErrorCode.EXTERNAL_API_TIMEOUT);
    }

    @Test
    void 같은_좌표의_Haversine_거리는_0이다() {
        assertThat(NearbyFestivalService.haversineKm(
                        CONTEST_LAT,
                        CONTEST_LNG,
                        CONTEST_LAT,
                        CONTEST_LNG))
                .isZero();
    }

    private Contest contest(BigDecimal lat, BigDecimal lng) {
        Instant updatedAt = Instant.parse("2026-08-01T00:00:00Z");
        Contest contest = Contest.create("contest-key", updatedAt);
        contest.update(
                "contest-key",
                "테스트 대회",
                "서울",
                "테스트 경기장",
                null,
                lat,
                lng,
                CONTEST_DATE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ContestCategory.ROAD,
                updatedAt,
                updatedAt);
        return contest;
    }

    private Festival festival(
            String contentId,
            LocalDate startDate,
            LocalDate endDate,
            String lat) {
        return new Festival(
                contentId,
                "축제 " + contentId,
                startDate,
                endDate,
                new BigDecimal(lat),
                CONTEST_LNG,
                null,
                "");
    }

    private void assertErrorCode(ThrowingCall call, ErrorCode expected) {
        assertThatThrownBy(call::invoke)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void invoke();
    }
}

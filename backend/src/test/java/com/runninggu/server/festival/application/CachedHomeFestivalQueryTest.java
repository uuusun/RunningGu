package com.runninggu.server.festival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import com.runninggu.server.festival.domain.HomeFestival;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CachedHomeFestivalQueryTest {

    private static final YearMonth YEAR_MONTH = YearMonth.of(2026, 8);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

    @Mock
    private FestivalProvider festivalProvider;

    private CachedHomeFestivalQuery query;

    @BeforeEach
    void setUp() {
        query = new CachedHomeFestivalQuery(festivalProvider);
    }

    @Test
    void 월_겹침을_필터하고_지역이_없어도_축제를_유지하며_표시순으로_정렬한다() {
        given(festivalProvider.searchStartingFrom(YEAR_MONTH.atDay(1))).willReturn(List.of(
                festival("upcoming", LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 23), "대구광역시 중구"),
                festival("ended", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), "부산광역시 해운대구"),
                festival("ongoing", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 25), "서울특별시 종로구"),
                festival("cross-boundary", LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 1), "인천광역시 남동구"),
                festival("outside", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), "경기도 수원시"),
                festival("unknown-region", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), "알수없는지역 행사장"),
                festival("missing-region", LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5), null)));

        List<HomeFestival> result = query.find(YEAR_MONTH, TODAY);

        assertThat(result)
                .extracting(HomeFestival::contentId)
                .containsExactly(
                        "ongoing",
                        "cross-boundary",
                        "ended",
                        "unknown-region",
                        "missing-region",
                        "upcoming");
        assertThat(result)
                .extracting(HomeFestival::region)
                .containsExactly("서울", "인천", "부산", "", "", "대구");
        assertThat(result)
                .extracting(HomeFestival::inProgress)
                .containsExactly(true, false, false, false, false, false);
    }

    @Test
    void 외부_일반오류와_타임아웃을_구분한다() {
        given(festivalProvider.searchStartingFrom(YEAR_MONTH.atDay(1)))
                .willThrow(new FestivalProviderException(Reason.ERROR))
                .willThrow(new FestivalProviderException(Reason.TIMEOUT));

        assertErrorCode(
                () -> query.find(YEAR_MONTH, TODAY),
                ErrorCode.EXTERNAL_API_ERROR);
        assertErrorCode(
                () -> query.find(YEAR_MONTH, TODAY),
                ErrorCode.EXTERNAL_API_TIMEOUT);
    }

    private Festival festival(
            String contentId,
            LocalDate startDate,
            LocalDate endDate,
            String address) {
        return new Festival(
                contentId,
                "축제 " + contentId,
                startDate,
                endDate,
                null,
                null,
                "https://example.test/" + contentId + ".jpg",
                address);
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

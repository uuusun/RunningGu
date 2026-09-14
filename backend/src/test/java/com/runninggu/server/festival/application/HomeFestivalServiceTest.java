package com.runninggu.server.festival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.runninggu.server.common.config.ClockConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.festival.domain.HomeFestival;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HomeFestivalServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final YearMonth CURRENT_MONTH = YearMonth.of(2026, 8);

    @Mock
    private CachedHomeFestivalQuery cachedQuery;

    @Mock
    private FestivalOfficialUrlQuery officialUrlQuery;

    private HomeFestivalService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-20T15:00:00Z"),
                ClockConfig.KST);
        service = new HomeFestivalService(cachedQuery, officialUrlQuery, clock);
    }

    @Test
    void parameter를_생략하면_KST_현재_월과_기본_6건을_사용한다() {
        List<HomeFestival> cached = IntStream.rangeClosed(1, 7)
                .mapToObj(index -> new HomeFestival(
                        "festival-" + index,
                        "축제 " + index,
                        TODAY,
                        TODAY,
                        "서울",
                        null,
                        true,
                        null))
                .toList();
        given(cachedQuery.find(CURRENT_MONTH, TODAY))
                .willReturn(cached);

        assertThat(service.find(null, null)).hasSize(HomeFestivalService.DEFAULT_SIZE);

        verify(cachedQuery).find(CURRENT_MONTH, TODAY);
    }

    @Test
    void 공식_페이지는_노출되는_축제에만_붙이고_없으면_null이다() {
        List<HomeFestival> cached = IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new HomeFestival(
                        "festival-" + index,
                        "축제 " + index,
                        TODAY,
                        TODAY,
                        "서울",
                        null,
                        true,
                        null))
                .toList();
        given(cachedQuery.find(CURRENT_MONTH, TODAY)).willReturn(cached);
        Map<String, String> urls = new HashMap<>();
        urls.put("festival-1", "https://festival-1.test");
        urls.put("festival-2", null);
        // size 로 잘려 나간 festival-3 은 묻지 않는다 — detailCommon2 를 부르면 쿼터가 샌다 (SPEC §9.5)
        given(officialUrlQuery.findAll(List.of("festival-1", "festival-2"))).willReturn(urls);

        List<HomeFestival> result = service.find(null, 2);

        assertThat(result)
                .extracting(HomeFestival::officialUrl)
                .containsExactly("https://festival-1.test", null);
    }

    @Test
    void 명시한_연월로_조회한다() {
        YearMonth requestedMonth = YearMonth.of(2026, 9);
        given(cachedQuery.find(requestedMonth, TODAY)).willReturn(List.of());

        service.find("2026-09", 10);

        verify(cachedQuery).find(requestedMonth, TODAY);
    }

    @Test
    void yearMonth는_정확한_YYYY_MM_형식만_허용한다() {
        assertValidation(() -> service.find("2026-8", 6));
        assertValidation(() -> service.find("2026/08", 6));
        assertValidation(() -> service.find("", 6));

        verifyNoInteractions(cachedQuery);
    }

    @Test
    void size는_1부터_20까지만_허용한다() {
        assertValidation(() -> service.find("2026-08", 0));
        assertValidation(() -> service.find("2026-08", 21));

        verifyNoInteractions(cachedQuery);
    }

    private void assertValidation(ThrowingCall call) {
        assertThatThrownBy(call::invoke)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void invoke();
    }
}

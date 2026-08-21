package com.runninggu.server.poi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PoiServiceTest {

    private CachedPoiSearchService cachedSearchService;
    private PoiService service;

    @BeforeEach
    void setUp() {
        cachedSearchService = mock(CachedPoiSearchService.class);
        service = new PoiService(cachedSearchService);
    }

    @Test
    void query_앞뒤_공백을_제거한_요청만_캐시_서비스로_보낸다() {
        PoiSearchCriteria expected = new PoiSearchCriteria(
                PoiCategory.LODGING,
                new BigDecimal("36.4912"),
                new BigDecimal("127.2714"),
                8_000,
                "세종 호텔",
                8);
        given(cachedSearchService.search(expected)).willReturn(new PoiSearchResult(List.of()));

        service.search(
                PoiCategory.LODGING,
                expected.lat(),
                expected.lng(),
                8_000,
                "  세종 호텔  ",
                8);

        verify(cachedSearchService).search(expected);
    }

    @Test
    void 좌표_반경_개수와_두_글자_미만_query를_거부한다() {
        assertValidation(new BigDecimal("90.1"), new BigDecimal("127"), 8_000, null, 8);
        assertValidation(new BigDecimal("37"), new BigDecimal("180.1"), 8_000, null, 8);
        assertValidation(new BigDecimal("37"), new BigDecimal("127"), 0, null, 8);
        assertValidation(new BigDecimal("37"), new BigDecimal("127"), 8_000, null, 21);
        assertValidation(new BigDecimal("37"), new BigDecimal("127"), 8_000, " 가 ", 8);
        assertValidation(new BigDecimal("37"), new BigDecimal("127"), 8_000, "   ", 8);
    }

    private void assertValidation(
            BigDecimal lat,
            BigDecimal lng,
            int radius,
            String query,
            int size) {
        assertThatThrownBy(() -> service.search(
                        PoiCategory.TOUR,
                        lat,
                        lng,
                        radius,
                        query,
                        size))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}

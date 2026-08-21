package com.runninggu.server.geocode.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.geocode.application.GeocodeProviderException.Reason;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeocodeServiceTest {

    @Mock
    private GeocodeProvider provider;

    private GeocodeService service;

    @BeforeEach
    void setUp() {
        service = new GeocodeService(provider);
    }

    @Test
    void 누락되거나_공백인_query는_검증오류다() {
        assertErrorCode(() -> service.geocode(null), ErrorCode.VALIDATION_FAILED);
        assertErrorCode(() -> service.geocode("   "), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 정상_빈_검색은_NO_RESULT다() {
        given(provider.findFirst("없는 장소")).willReturn(Optional.empty());

        assertErrorCode(() -> service.geocode("없는 장소"), ErrorCode.NO_RESULT);
    }

    @Test
    void 외부_일반오류와_타임아웃을_구분한다() {
        given(provider.findFirst("일반 오류"))
                .willThrow(new GeocodeProviderException(Reason.ERROR));
        given(provider.findFirst("타임아웃"))
                .willThrow(new GeocodeProviderException(Reason.TIMEOUT));

        assertErrorCode(() -> service.geocode("일반 오류"), ErrorCode.EXTERNAL_API_ERROR);
        assertErrorCode(() -> service.geocode("타임아웃"), ErrorCode.EXTERNAL_API_TIMEOUT);
    }

    private void assertErrorCode(ThrowingCall call, ErrorCode expected) {
        assertThatThrownBy(call::invoke)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void invoke();
    }
}

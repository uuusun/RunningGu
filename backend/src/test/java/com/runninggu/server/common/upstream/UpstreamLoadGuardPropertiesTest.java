package com.runninggu.server.common.upstream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UpstreamLoadGuardPropertiesTest {

    @Test
    void 비활성화가_기본이면_환경과_상한이_없어도_허용한다() {
        assertThatCode(() -> new UpstreamLoadGuardProperties(
                        false,
                        null,
                        null,
                        null,
                        null))
                .doesNotThrowAnyException();
    }

    @Test
    void 활성화는_staging에서만_허용한다() {
        assertThatThrownBy(() -> enabledProperties("production", "load-20260905", 10, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("upstream load guard can only be enabled in staging");
        assertThatThrownBy(() -> enabledProperties("STAGING", "load-20260905", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> enabledProperties("staging", "load-20260905", 10, 10))
                .doesNotThrowAnyException();
    }

    @Test
    void 활성화는_안전한_run_id만_허용한다() {
        assertThatThrownBy(() -> enabledProperties("staging", "", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabledProperties("staging", "load run", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabledProperties("staging", "가나다", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabledProperties("staging", "a".repeat(65), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> enabledProperties("staging", "run_2026-09.05", 10, 10))
                .doesNotThrowAnyException();
    }

    @Test
    void 활성화는_kakao_전체와_모든_endpoint의_양수_상한을_요구한다() {
        assertThatThrownBy(() -> enabledProperties("staging", "run-1", 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kakao-total-limit");

        assertThatThrownBy(() -> enabledProperties("staging", "run-1", 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoints.kakao-category");

        assertThatThrownBy(() -> new UpstreamLoadGuardProperties(
                        true,
                        "staging",
                        "run-1",
                        10,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint limits");
    }

    @Test
    void 승인된_최대값을_넘는_설정은_기동_전에_거부한다() {
        assertThatThrownBy(() -> enabledProperties("staging", "run-1", 5_001, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no greater than 5000");

        assertThatThrownBy(() -> propertiesWithSeparateLimits(2_001, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no greater than 2000");

        assertThatThrownBy(() -> propertiesWithSeparateLimits(10, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no greater than 100");

        assertThatCode(() -> propertiesWithSeparateLimits(2_000, 100))
                .doesNotThrowAnyException();
    }

    private UpstreamLoadGuardProperties enabledProperties(
            String environment,
            String runId,
            Integer kakaoTotal,
            Integer endpointLimit) {
        return new UpstreamLoadGuardProperties(
                true,
                environment,
                runId,
                kakaoTotal,
                new UpstreamLoadGuardProperties.EndpointLimits(
                        endpointLimit,
                        endpointLimit,
                        endpointLimit,
                        endpointLimit,
                        endpointLimit,
                        endpointLimit,
                        endpointLimit,
                        endpointLimit));
    }

    private UpstreamLoadGuardProperties propertiesWithSeparateLimits(
            Integer kakaoEndpointLimit,
            Integer ktoEndpointLimit) {
        return new UpstreamLoadGuardProperties(
                true,
                "staging",
                "run-1",
                5_000,
                new UpstreamLoadGuardProperties.EndpointLimits(
                        kakaoEndpointLimit,
                        kakaoEndpointLimit,
                        kakaoEndpointLimit,
                        kakaoEndpointLimit,
                        ktoEndpointLimit,
                        ktoEndpointLimit,
                        ktoEndpointLimit,
                        ktoEndpointLimit));
    }
}

package com.runninggu.server.common.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.upstream.UpstreamLoadGuardException.Reason;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UpstreamLoadGuardTest {

    private static final URI KAKAO_CATEGORY = URI.create(
            "https://dapi.kakao.com/v2/local/search/category.json?query=fixture");
    private static final URI KAKAO_KEYWORD = URI.create(
            "https://dapi.kakao.com/v2/local/search/keyword.json?query=fixture");
    private static final URI KAKAO_USER_ME =
            URI.create("https://kapi.kakao.com/v2/user/me");
    private static final URI KTO_FESTIVAL = URI.create(
            "https://apis.data.go.kr/B551011/KorService2/searchFestival2");

    @Test
    void 비활성화이면_unknown과_비대상_URI도_그대로_통과한다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(disabledProperties());

        assertThat(guard.reserve(
                                URI.create("https://apis.data.go.kr/not-allowed"),
                                UpstreamProvider.KTO)
                        .monitored())
                .isFalse();
        assertThat(guard.reserve(
                                URI.create("http://127.0.0.1:8989/route"),
                                UpstreamProvider.KAKAO)
                        .monitored())
                .isFalse();
        guard.tripKtoResultCode(UpstreamEndpoint.KTO_SEARCH_FESTIVAL);

        assertThat(guard.isTripped()).isFalse();
    }

    @Test
    void 활성화이면_expected_provider와_다른_provider나_host를_차단한다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));

        assertThatThrownBy(() -> guard.reserve(KTO_FESTIVAL, UpstreamProvider.KAKAO))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.UNKNOWN_ENDPOINT));

        UpstreamLoadGuard wrongHost = new UpstreamLoadGuard(enabledProperties(10, 10));
        assertThatThrownBy(() -> wrongHost.reserve(
                        URI.create("https://example.test/path"),
                        UpstreamProvider.KTO))
                .isInstanceOf(UpstreamLoadGuardException.class);
    }

    @Test
    void endpoint_N회는_예약하고_N_plus_1은_네트워크_전에_전역_trip한다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 2));

        assertThat(guard.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO).endpointCount())
                .isEqualTo(1);
        assertThat(guard.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO).endpointCount())
                .isEqualTo(2);
        assertThat(guard.isTripped()).isFalse();

        assertThatThrownBy(() -> guard.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.BUDGET_EXHAUSTED));
        assertThat(guard.count(UpstreamEndpoint.KAKAO_CATEGORY)).isEqualTo(2);
        assertThat(guard.isTripped()).isTrue();
    }

    @Test
    void Kakao_전체_상한은_endpoint를_합산한다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(2, 10));

        guard.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO);
        guard.reserve(KAKAO_KEYWORD, UpstreamProvider.KAKAO);

        assertThatThrownBy(() -> guard.reserve(KAKAO_USER_ME, UpstreamProvider.KAKAO))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.BUDGET_EXHAUSTED));
        assertThat(guard.kakaoTotalCount()).isEqualTo(2);
        assertThat(guard.count(UpstreamEndpoint.KAKAO_USER_ME)).isZero();
    }

    @Test
    void guarded_host의_unknown_path는_즉시_trip하고_이후_정상_path도_막는다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));

        assertThatThrownBy(() -> guard.reserve(
                        URI.create("https://apis.data.go.kr/B551011/unknown?serviceKey=secret"),
                        UpstreamProvider.KTO))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.UNKNOWN_ENDPOINT));

        assertThatThrownBy(() -> guard.reserve(KTO_FESTIVAL, UpstreamProvider.KTO))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.GLOBAL_TRIPPED));
        assertThat(guard.count(UpstreamEndpoint.KTO_SEARCH_FESTIVAL)).isZero();
    }

    @Test
    void 첫_429와_5xx와_timeout은_각각_전역_trip한다() {
        UpstreamLoadGuard rateLimited = new UpstreamLoadGuard(enabledProperties(10, 10));
        assertThatThrownBy(() -> rateLimited.recordHttpStatus(
                        rateLimited.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO),
                        429))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.HTTP_RISK_SIGNAL));
        assertThat(rateLimited.isTripped()).isTrue();

        UpstreamLoadGuard serverError = new UpstreamLoadGuard(enabledProperties(10, 10));
        assertThatThrownBy(() -> serverError.recordHttpStatus(
                        serverError.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO),
                        503))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(serverError.isTripped()).isTrue();

        UpstreamLoadGuard timeout = new UpstreamLoadGuard(enabledProperties(10, 10));
        assertThatThrownBy(() -> timeout.recordTimeout(
                        timeout.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO)))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.TIMEOUT_SIGNAL));
        assertThat(timeout.isTripped()).isTrue();
    }

    @Test
    void guard_예외는_RestClientException이_아니며_비밀_없는_고정_메시지만_갖는다() {
        UpstreamLoadGuardException exception =
                new UpstreamLoadGuardException(Reason.HTTP_RISK_SIGNAL);

        assertThat(exception)
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasMessage("upstream load guard detected an unsafe HTTP result")
                .hasNoCause();
    }

    @Test
    void KTO_parser의_실패_resultCode_신호는_값을_받지_않고_전역_trip한다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        guard.reserve(KTO_FESTIVAL, UpstreamProvider.KTO);

        assertThatThrownBy(() -> guard.tripKtoResultCode(
                        UpstreamEndpoint.KTO_SEARCH_FESTIVAL))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(Reason.KTO_RESULT_CODE));

        assertThat(guard.isTripped()).isTrue();
        assertThatThrownBy(() -> guard.reserve(KTO_FESTIVAL, UpstreamProvider.KTO))
                .isInstanceOf(UpstreamLoadGuardException.class);
    }

    @Test
    void 동시_예약도_endpoint_상한을_넘지_않는다() throws Exception {
        int limit = 40;
        int attempts = 160;
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(1_000, limit));
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        guard.reserve(KAKAO_CATEGORY, UpstreamProvider.KAKAO);
                        return true;
                    } catch (UpstreamLoadGuardException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }

            assertThat(allowed).isEqualTo(limit);
            assertThat(guard.count(UpstreamEndpoint.KAKAO_CATEGORY)).isEqualTo(limit);
            assertThat(guard.isTripped()).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UpstreamLoadGuardProperties disabledProperties() {
        return new UpstreamLoadGuardProperties(false, null, null, null, null);
    }

    private UpstreamLoadGuardProperties enabledProperties(int kakaoTotal, int endpointLimit) {
        return new UpstreamLoadGuardProperties(
                true,
                "staging",
                "load-20260905",
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
}

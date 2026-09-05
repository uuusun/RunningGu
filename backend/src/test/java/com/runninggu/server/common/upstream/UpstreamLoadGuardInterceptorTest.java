package com.runninggu.server.common.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

@ExtendWith(OutputCaptureExtension.class)
class UpstreamLoadGuardInterceptorTest {

    private static final URI KAKAO_CATEGORY = URI.create(
            "https://dapi.kakao.com/v2/local/search/category.json"
                    + "?query=NEVER_LOG_QUERY&x=NEVER_LOG_COORDINATE");

    @Test
    void 비활성화이면_status를_읽거나_response를_wrap하지_않고_완전히_통과시킨다()
            throws IOException {
        UpstreamLoadGuardInterceptor interceptor = new UpstreamLoadGuardInterceptor(
                new UpstreamLoadGuard(disabledProperties()),
                UpstreamProvider.KAKAO);
        AtomicInteger statusReads = new AtomicInteger();
        ClientHttpResponse original =
                response(new ByteArrayInputStream(new byte[0]), statusReads);

        ClientHttpResponse returned = interceptor.intercept(
                request(URI.create("https://example.test/not-allowlisted")),
                new byte[0],
                (ignored, body) -> original);

        assertThat(returned).isSameAs(original);
        assertThat(statusReads).hasValue(0);
    }

    @Test
    void 첫_429_응답은_호출자에게_반환하지만_재시도는_실행_전에_막는다()
            throws IOException {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        AtomicInteger networkExecutions = new AtomicInteger();
        MockClientHttpRequest request = request(KAKAO_CATEGORY);

        assertThatThrownBy(() -> interceptor.intercept(
                        request,
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(
                                    new byte[0],
                                    HttpStatus.TOO_MANY_REQUESTS);
                        }))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(UpstreamLoadGuardException.Reason.HTTP_RISK_SIGNAL));
        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (ignored, body) -> {
                    networkExecutions.incrementAndGet();
                    return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                }))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(networkExecutions).hasValue(1);
    }

    @Test
    void timeout은_triggering_attempt에서_즉시_guard_예외로_바꾸고_후속_호출을_막는다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        AtomicInteger networkExecutions = new AtomicInteger();
        SocketTimeoutException timeout =
                new SocketTimeoutException("NEVER_LOG_EXCEPTION_MESSAGE");

        assertThatThrownBy(() -> interceptor.intercept(
                        request(KAKAO_CATEGORY),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            throw timeout;
                        }))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> {
                            assertThat(exception.reason())
                                    .isEqualTo(UpstreamLoadGuardException.Reason.TIMEOUT_SIGNAL);
                            assertThat(exception.getCause()).isNull();
                        });

        assertThatThrownBy(() -> interceptor.intercept(
                        request(KAKAO_CATEGORY),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        }))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(networkExecutions).hasValue(1);
    }

    @Test
    void 첫_5xx도_triggering_attempt에서_즉시_guard_예외를_던진다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        AtomicInteger networkExecutions = new AtomicInteger();

        assertThatThrownBy(() -> interceptor.intercept(
                        request(KAKAO_CATEGORY),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(
                                    new byte[0],
                                    HttpStatus.SERVICE_UNAVAILABLE);
                        }))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(UpstreamLoadGuardException.Reason.HTTP_RISK_SIGNAL));
        assertThat(networkExecutions).hasValue(1);
        assertThat(guard.isTripped()).isTrue();
    }

    @Test
    void body_첫_read_timeout도_같은_attempt에서_즉시_trip한다(CapturedOutput output)
            throws IOException {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        ClientHttpResponse response = interceptor.intercept(
                request(KAKAO_CATEGORY),
                new byte[0],
                (ignored, body) -> response(new TimeoutAfterBytesInputStream(0)));

        assertThat(guard.isTripped()).isFalse();
        assertThat(output.getOut()).doesNotContain("class=HTTP_2XX");

        assertThatThrownBy(() -> response.getBody().read())
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(UpstreamLoadGuardException.Reason.TIMEOUT_SIGNAL));

        assertThat(guard.isTripped()).isTrue();
        assertThat(output.getOut())
                .contains("class=TIMEOUT")
                .contains("endpointCount=1")
                .contains("endpointLimit=10")
                .contains("providerCount=1")
                .contains("providerLimit=10")
                .doesNotContain("NEVER_LOG_BODY_TIMEOUT")
                .doesNotContain("dapi.kakao.com");
    }

    @Test
    void body_중간_read_timeout도_성공_COMPLETE없이_trip한다(CapturedOutput output)
            throws IOException {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        ClientHttpResponse response = interceptor.intercept(
                request(KAKAO_CATEGORY),
                new byte[0],
                (ignored, body) -> response(new TimeoutAfterBytesInputStream(1)));
        InputStream guardedBody = response.getBody();

        assertThat(guardedBody.read()).isEqualTo('A');
        assertThatThrownBy(guardedBody::read)
                .isInstanceOf(UpstreamLoadGuardException.class);

        assertThat(guard.isTripped()).isTrue();
        assertThat(output.getOut())
                .contains("class=TIMEOUT")
                .doesNotContain("class=HTTP_2XX")
                .doesNotContain("NEVER_LOG_BODY_TIMEOUT");
    }

    @Test
    void 정상_body는_EOF에서_한_번만_COMPLETE로_기록한다(CapturedOutput output)
            throws IOException {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        ClientHttpResponse response = interceptor.intercept(
                request(KAKAO_CATEGORY),
                new byte[0],
                (ignored, body) -> response(new ByteArrayInputStream(
                        "NEVER_LOG_BODY".getBytes(StandardCharsets.UTF_8))));

        assertThat(output.getOut()).doesNotContain("class=HTTP_2XX");
        assertThat(response.getBody().readAllBytes())
                .isEqualTo("NEVER_LOG_BODY".getBytes(StandardCharsets.UTF_8));
        response.close();

        assertThat(output.getOut())
                .contains("class=HTTP_2XX")
                .contains("endpointCount=1")
                .contains("endpointLimit=10")
                .contains("providerCount=1")
                .contains("providerLimit=10")
                .doesNotContain("NEVER_LOG_BODY");
    }

    @Test
    void KTO_로그는_endpoint_상한과_provider_상한_비적용을_고정값으로_남긴다(
            CapturedOutput output) throws IOException {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KTO);
        ClientHttpResponse response = interceptor.intercept(
                request(URI.create(
                        "https://apis.data.go.kr/B551011/KorService2/searchFestival2"
                                + "?serviceKey=NEVER_LOG_SERVICE_KEY")),
                new byte[0],
                (ignored, body) -> response(new ByteArrayInputStream(new byte[0])));

        assertThat(response.getBody().read()).isEqualTo(-1);

        assertThat(output.getOut())
                .contains("provider=KTO")
                .contains("endpoint=KTO_SEARCH_FESTIVAL")
                .contains("endpointCount=1")
                .contains("endpointLimit=10")
                .contains("providerCount=1")
                .contains("providerLimit=NONE")
                .doesNotContain("NEVER_LOG_SERVICE_KEY")
                .doesNotContain("apis.data.go.kr");
    }

    @Test
    void unknown_path와_상한_N_plus_1은_execution을_호출하지_않는다() throws IOException {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 1));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);
        AtomicInteger networkExecutions = new AtomicInteger();

        interceptor.intercept(request(KAKAO_CATEGORY), new byte[0], (ignored, body) -> {
            networkExecutions.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        });
        assertThatThrownBy(() -> interceptor.intercept(
                        request(KAKAO_CATEGORY),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        }))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(networkExecutions).hasValue(1);

        UpstreamLoadGuard unknownGuard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor unknownInterceptor =
                new UpstreamLoadGuardInterceptor(unknownGuard, UpstreamProvider.KAKAO);
        assertThatThrownBy(() -> unknownInterceptor.intercept(
                        request(URI.create("https://kapi.kakao.com/v2/unknown?token=NEVER_LOG_TOKEN")),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        }))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(networkExecutions).hasValue(1);
    }

    @Test
    void expected_provider_불일치와_unknown_host는_execution_전에_차단한다() {
        AtomicInteger networkExecutions = new AtomicInteger();
        UpstreamLoadGuardInterceptor kakaoInterceptor = new UpstreamLoadGuardInterceptor(
                new UpstreamLoadGuard(enabledProperties(10, 10)),
                UpstreamProvider.KAKAO);

        assertThatThrownBy(() -> kakaoInterceptor.intercept(
                        request(URI.create(
                                "https://apis.data.go.kr/B551011/KorService2/searchFestival2")),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        }))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(networkExecutions).hasValue(0);

        UpstreamLoadGuardInterceptor ktoInterceptor = new UpstreamLoadGuardInterceptor(
                new UpstreamLoadGuard(enabledProperties(10, 10)),
                UpstreamProvider.KTO);
        assertThatThrownBy(() -> ktoInterceptor.intercept(
                        request(URI.create("https://example.test/not-kto")),
                        new byte[0],
                        (ignored, body) -> {
                            networkExecutions.incrementAndGet();
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        }))
                .isInstanceOf(UpstreamLoadGuardException.class);
        assertThat(networkExecutions).hasValue(0);
    }

    @Test
    void 로그에는_허용된_필드만_남고_URI_query와_예외_메시지는_남지_않는다(
            CapturedOutput output) {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties(10, 10));
        UpstreamLoadGuardInterceptor interceptor =
                new UpstreamLoadGuardInterceptor(guard, UpstreamProvider.KAKAO);

        assertThatThrownBy(() -> interceptor.intercept(
                        request(KAKAO_CATEGORY),
                        new byte[0],
                        (ignored, body) -> {
                            throw new SocketTimeoutException("NEVER_LOG_EXCEPTION_MESSAGE");
                        }))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(UpstreamLoadGuardException.Reason.TIMEOUT_SIGNAL));

        assertThat(output.getOut())
                .contains("runId=load-20260905")
                .contains("provider=KAKAO")
                .contains("endpoint=KAKAO_CATEGORY")
                .contains("event=TRIP")
                .contains("class=TIMEOUT")
                .contains("elapsedMs=")
                .contains("endpointCount=1")
                .contains("endpointLimit=10")
                .contains("providerCount=1")
                .contains("providerLimit=10")
                .doesNotContain("NEVER_LOG_QUERY")
                .doesNotContain("NEVER_LOG_COORDINATE")
                .doesNotContain("NEVER_LOG_EXCEPTION_MESSAGE")
                .doesNotContain("dapi.kakao.com")
                .doesNotContain("category.json");
    }

    private MockClientHttpRequest request(URI uri) {
        return new MockClientHttpRequest(HttpMethod.GET, uri);
    }

    private ClientHttpResponse response(InputStream body) {
        return response(body, null);
    }

    private ClientHttpResponse response(InputStream body, AtomicInteger statusReads) {
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() {
                if (statusReads != null) {
                    statusReads.incrementAndGet();
                }
                return HttpStatus.OK;
            }

            @Override
            public String getStatusText() {
                return "OK";
            }

            @Override
            public void close() {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // 테스트 응답 close는 예외를 외부로 노출하지 않는다.
                }
            }

            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public HttpHeaders getHeaders() {
                return HttpHeaders.EMPTY;
            }
        };
    }

    private static final class TimeoutAfterBytesInputStream extends InputStream {

        private int remainingSuccessfulReads;

        private TimeoutAfterBytesInputStream(int successfulReads) {
            this.remainingSuccessfulReads = successfulReads;
        }

        @Override
        public int read() throws IOException {
            if (remainingSuccessfulReads > 0) {
                remainingSuccessfulReads--;
                return 'A';
            }
            throw new SocketTimeoutException("NEVER_LOG_BODY_TIMEOUT");
        }
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

    private UpstreamLoadGuardProperties disabledProperties() {
        return new UpstreamLoadGuardProperties(false, null, null, null, null);
    }
}

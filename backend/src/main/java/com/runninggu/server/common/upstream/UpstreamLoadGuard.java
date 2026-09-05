package com.runninggu.server.common.upstream;

import com.runninggu.server.common.upstream.UpstreamLoadGuardException.Reason;
import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** staging 부하 시험의 외부 호출 상한과 최초 위험 신호를 JVM 안에서 fail-closed로 집행한다. */
public final class UpstreamLoadGuard {

    private static final Logger log = LoggerFactory.getLogger(UpstreamLoadGuard.class);

    private final UpstreamLoadGuardProperties properties;
    private final LongSupplier nanoTime;
    private final Map<UpstreamEndpoint, Integer> endpointCounts =
            new EnumMap<>(UpstreamEndpoint.class);

    private int kakaoTotalCount;
    private int ktoTotalCount;
    private TerminalState terminalState;

    public UpstreamLoadGuard(UpstreamLoadGuardProperties properties) {
        this(properties, System::nanoTime);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    UpstreamLoadGuard(
            UpstreamLoadGuardProperties properties,
            LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    /** 실제 HTTP 실행 직전에 호출하며, 허용된 N번째 시도까지 원자적으로 예약한다. */
    public synchronized UpstreamAttempt reserve(
            URI uri,
            UpstreamProvider expectedProvider) {
        if (!properties.enabled()) {
            return UpstreamAttempt.passThrough();
        }
        Objects.requireNonNull(expectedProvider, "expectedProvider");

        if (terminalState != null) {
            logEvent(
                    expectedProvider,
                    "UNKNOWN",
                    GuardEvent.BLOCK,
                    terminalState == TerminalState.BUDGET_EXHAUSTED
                            ? GuardClass.BUDGET_EXHAUSTED
                            : GuardClass.GLOBAL_TRIPPED,
                    0L,
                    0,
                    providerCount(expectedProvider),
                    "UNKNOWN",
                    providerLimit(expectedProvider));
            throw new UpstreamLoadGuardException(
                    terminalState == TerminalState.BUDGET_EXHAUSTED
                            ? Reason.BUDGET_EXHAUSTED
                            : Reason.GLOBAL_TRIPPED);
        }

        UpstreamEndpoint endpoint = UpstreamEndpoint.resolve(uri).orElse(null);
        if (endpoint == null || endpoint.provider() != expectedProvider) {
            terminalState = TerminalState.UNKNOWN_ENDPOINT;
            logEvent(
                    expectedProvider,
                    "UNKNOWN",
                    GuardEvent.TRIP,
                    GuardClass.UNKNOWN_ENDPOINT,
                    0L,
                    0,
                    providerCount(expectedProvider),
                    "UNKNOWN",
                    providerLimit(expectedProvider));
            throw new UpstreamLoadGuardException(Reason.UNKNOWN_ENDPOINT);
        }

        int currentEndpointCount = endpointCounts.getOrDefault(endpoint, 0);
        boolean endpointExhausted = currentEndpointCount >= properties.limitFor(endpoint);
        boolean providerExhausted = endpoint.provider() == UpstreamProvider.KAKAO
                && kakaoTotalCount >= properties.kakaoTotalLimit();
        if (endpointExhausted || providerExhausted) {
            terminalState = TerminalState.BUDGET_EXHAUSTED;
            logEvent(
                    endpoint.provider(),
                    endpoint.name(),
                    GuardEvent.TRIP,
                    GuardClass.BUDGET_EXHAUSTED,
                    0L,
                    currentEndpointCount,
                    providerCount(endpoint.provider()),
                    Integer.toString(properties.limitFor(endpoint)),
                    providerLimit(endpoint.provider()));
            throw new UpstreamLoadGuardException(Reason.BUDGET_EXHAUSTED);
        }

        int endpointCount = currentEndpointCount + 1;
        endpointCounts.put(endpoint, endpointCount);
        int providerCount;
        if (endpoint.provider() == UpstreamProvider.KAKAO) {
            providerCount = ++kakaoTotalCount;
        } else {
            providerCount = ++ktoTotalCount;
        }
        return UpstreamAttempt.monitored(
                endpoint,
                nanoTime.getAsLong(),
                endpointCount,
                providerCount);
    }

    public synchronized void recordHttpStatus(UpstreamAttempt attempt, int statusCode) {
        if (!isMonitored(attempt)) {
            return;
        }
        GuardClass classification = classifyStatus(statusCode);
        GuardEvent event = GuardEvent.COMPLETE;
        if (statusCode == 429 || isServerError(statusCode)) {
            terminalState = statusCode == 429
                    ? TerminalState.HTTP_429
                    : TerminalState.HTTP_5XX;
            event = GuardEvent.TRIP;
        }
        logAttempt(attempt, event, classification);
        if (statusCode == 429 || isServerError(statusCode)) {
            throw new UpstreamLoadGuardException(Reason.HTTP_RISK_SIGNAL);
        }
    }

    public synchronized void recordTimeout(UpstreamAttempt attempt) {
        if (!isMonitored(attempt)) {
            return;
        }
        terminalState = TerminalState.TIMEOUT;
        logAttempt(attempt, GuardEvent.TRIP, GuardClass.TIMEOUT);
        throw new UpstreamLoadGuardException(Reason.TIMEOUT_SIGNAL);
    }

    public synchronized void recordIoFailure(UpstreamAttempt attempt) {
        if (!isMonitored(attempt)) {
            return;
        }
        logAttempt(attempt, GuardEvent.COMPLETE, GuardClass.IO_FAILURE);
    }

    /** HTTP 200 본문의 KTO 실패 resultCode를 parser가 값 자체 없이 신호한다. */
    public synchronized void tripKtoResultCode(UpstreamEndpoint endpoint) {
        if (!properties.enabled()) {
            return;
        }
        if (endpoint == null || endpoint.provider() != UpstreamProvider.KTO) {
            throw new IllegalArgumentException("KTO endpoint is required");
        }
        terminalState = TerminalState.KTO_RESULT_CODE;
        logEvent(
                endpoint.provider(),
                endpoint.name(),
                GuardEvent.TRIP,
                GuardClass.KTO_RESULT_CODE,
                0L,
                endpointCounts.getOrDefault(endpoint, 0),
                providerCount(UpstreamProvider.KTO),
                Integer.toString(properties.limitFor(endpoint)),
                providerLimit(UpstreamProvider.KTO));
        throw new UpstreamLoadGuardException(Reason.KTO_RESULT_CODE);
    }

    synchronized boolean isTripped() {
        return terminalState != null;
    }

    synchronized int count(UpstreamEndpoint endpoint) {
        return endpointCounts.getOrDefault(endpoint, 0);
    }

    synchronized int kakaoTotalCount() {
        return kakaoTotalCount;
    }

    private boolean isMonitored(UpstreamAttempt attempt) {
        return properties.enabled() && attempt != null && attempt.monitored();
    }

    private int providerCount(UpstreamProvider provider) {
        if (provider == UpstreamProvider.KAKAO) {
            return kakaoTotalCount;
        }
        return ktoTotalCount;
    }

    private GuardClass classifyStatus(int statusCode) {
        if (statusCode == 429) {
            return GuardClass.HTTP_429;
        }
        if (isServerError(statusCode)) {
            return GuardClass.HTTP_5XX;
        }
        if (statusCode >= 400) {
            return GuardClass.HTTP_4XX;
        }
        if (statusCode >= 300) {
            return GuardClass.HTTP_3XX;
        }
        if (statusCode >= 200) {
            return GuardClass.HTTP_2XX;
        }
        return GuardClass.HTTP_OTHER;
    }

    private boolean isServerError(int statusCode) {
        return statusCode >= 500 && statusCode <= 599;
    }

    boolean isImmediateHttpTrip(int statusCode) {
        return statusCode == 429 || isServerError(statusCode);
    }

    private void logAttempt(
            UpstreamAttempt attempt,
            GuardEvent event,
            GuardClass classification) {
        logEvent(
                attempt.endpoint().provider(),
                attempt.endpoint().name(),
                event,
                classification,
                elapsedMillis(attempt),
                attempt.endpointCount(),
                attempt.providerCount(),
                Integer.toString(properties.limitFor(attempt.endpoint())),
                providerLimit(attempt.endpoint().provider()));
    }

    private long elapsedMillis(UpstreamAttempt attempt) {
        return Math.max(0L, nanoTime.getAsLong() - attempt.startedNanos()) / 1_000_000L;
    }

    private void logEvent(
            UpstreamProvider provider,
            String endpoint,
            GuardEvent event,
            GuardClass classification,
            long elapsedMillis,
            int endpointCount,
            int providerCount,
            String endpointLimit,
            String providerLimit) {
        log.info(
                "runId={} provider={} endpoint={} event={} class={} elapsedMs={} endpointCount={} endpointLimit={} providerCount={} providerLimit={}",
                properties.runId(),
                provider,
                endpoint,
                event,
                classification,
                elapsedMillis,
                endpointCount,
                endpointLimit,
                providerCount,
                providerLimit);
    }

    private String providerLimit(UpstreamProvider provider) {
        return provider == UpstreamProvider.KAKAO
                ? Integer.toString(properties.kakaoTotalLimit())
                : "NONE";
    }

    private enum GuardEvent {
        COMPLETE,
        TRIP,
        BLOCK
    }

    private enum GuardClass {
        HTTP_2XX,
        HTTP_3XX,
        HTTP_4XX,
        HTTP_429,
        HTTP_5XX,
        HTTP_OTHER,
        TIMEOUT,
        IO_FAILURE,
        KTO_RESULT_CODE,
        UNKNOWN_ENDPOINT,
        BUDGET_EXHAUSTED,
        GLOBAL_TRIPPED
    }

    private enum TerminalState {
        BUDGET_EXHAUSTED,
        HTTP_429,
        HTTP_5XX,
        TIMEOUT,
        KTO_RESULT_CODE,
        UNKNOWN_ENDPOINT
    }
}

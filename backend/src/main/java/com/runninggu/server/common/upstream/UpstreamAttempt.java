package com.runninggu.server.common.upstream;

/** 한 번의 실제 외부 HTTP 시도에 예약된 불변 계수 정보다. */
public record UpstreamAttempt(
        boolean monitored,
        UpstreamEndpoint endpoint,
        long startedNanos,
        int endpointCount,
        int providerCount) {

    private static final UpstreamAttempt PASS_THROUGH =
            new UpstreamAttempt(false, null, 0L, 0, 0);

    static UpstreamAttempt passThrough() {
        return PASS_THROUGH;
    }

    static UpstreamAttempt monitored(
            UpstreamEndpoint endpoint,
            long startedNanos,
            int endpointCount,
            int providerCount) {
        return new UpstreamAttempt(
                true,
                endpoint,
                startedNanos,
                endpointCount,
                providerCount);
    }
}

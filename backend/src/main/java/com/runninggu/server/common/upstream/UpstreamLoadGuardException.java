package com.runninggu.server.common.upstream;

/** 외부 요청을 네트워크 실행 전에 차단했음을 나타내는 안전한 고정 메시지 예외다. */
public final class UpstreamLoadGuardException extends RuntimeException {

    private final Reason reason;

    UpstreamLoadGuardException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNKNOWN_ENDPOINT("upstream load guard rejected an unknown endpoint"),
        BUDGET_EXHAUSTED("upstream load guard budget is exhausted"),
        HTTP_RISK_SIGNAL("upstream load guard detected an unsafe HTTP result"),
        TIMEOUT_SIGNAL("upstream load guard detected an upstream timeout"),
        KTO_RESULT_CODE("upstream load guard detected a rejected KTO result"),
        GLOBAL_TRIPPED("upstream load guard is tripped");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}

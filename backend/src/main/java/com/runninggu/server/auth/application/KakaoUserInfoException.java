package com.runninggu.server.auth.application;

public class KakaoUserInfoException extends RuntimeException {

    public enum Reason {
        INVALID_TOKEN,
        TIMEOUT,
        ERROR
    }

    private final Reason reason;

    public KakaoUserInfoException(Reason reason) {
        this.reason = reason;
    }

    public KakaoUserInfoException(Reason reason, Throwable cause) {
        super(cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

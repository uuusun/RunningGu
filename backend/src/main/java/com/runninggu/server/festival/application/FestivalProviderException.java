package com.runninggu.server.festival.application;

import java.util.Objects;

/** 외부 축제 조회 실패를 공개 HTTP 오류와 분리해 전달한다. */
public class FestivalProviderException extends RuntimeException {

    public enum Reason {
        ERROR,
        TIMEOUT
    }

    private final Reason reason;

    public FestivalProviderException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
    }

    public FestivalProviderException(Reason reason) {
        super(Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

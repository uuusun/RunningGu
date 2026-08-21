package com.runninggu.server.geocode.application;

import java.util.Objects;

/** 외부 어댑터 실패를 공개 HTTP 오류와 분리해 전달한다. */
public class GeocodeProviderException extends RuntimeException {

    public enum Reason {
        ERROR,
        TIMEOUT
    }

    private final Reason reason;

    public GeocodeProviderException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
    }

    public GeocodeProviderException(Reason reason) {
        super(Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

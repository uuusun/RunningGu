package com.runninggu.server.poi.application;

import java.util.Objects;

/** 외부 POI 어댑터 실패를 공개 HTTP 오류와 분리해 전달한다. */
public class PoiSourceException extends RuntimeException {

    public enum Reason {
        ERROR,
        TIMEOUT
    }

    private final Reason reason;

    public PoiSourceException(Reason reason) {
        super(Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public PoiSourceException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

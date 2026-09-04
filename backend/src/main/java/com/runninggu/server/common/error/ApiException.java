package com.runninggu.server.common.error;

import java.util.Objects;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode).title());
    }

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

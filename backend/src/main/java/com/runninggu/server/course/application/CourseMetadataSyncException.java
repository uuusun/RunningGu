package com.runninggu.server.course.application;

import java.util.Objects;

public class CourseMetadataSyncException extends RuntimeException {

    public enum Reason {
        MISSING_KEY,
        TIMEOUT,
        HTTP_ERROR,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public CourseMetadataSyncException(Reason reason) {
        this(reason, null);
    }

    public CourseMetadataSyncException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }
}

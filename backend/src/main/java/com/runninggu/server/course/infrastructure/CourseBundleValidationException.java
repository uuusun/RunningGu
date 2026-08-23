package com.runninggu.server.course.infrastructure;

public class CourseBundleValidationException extends IllegalStateException {

    public CourseBundleValidationException(String message) {
        super(message);
    }

    public CourseBundleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.runninggu.server.course.application;

public class OsmRouteSourceException extends RuntimeException {

    public OsmRouteSourceException(String message) {
        super(message);
    }

    public OsmRouteSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}

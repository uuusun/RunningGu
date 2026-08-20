package com.runninggu.server.contest.infrastructure;

public class ContestSnapshotReadException extends RuntimeException {

    public ContestSnapshotReadException(String message) {
        super(message);
    }

    public ContestSnapshotReadException(String message, Throwable cause) {
        super(message, cause);
    }
}

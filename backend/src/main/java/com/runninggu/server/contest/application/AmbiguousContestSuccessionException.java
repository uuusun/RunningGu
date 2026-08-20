package com.runninggu.server.contest.application;

public class AmbiguousContestSuccessionException extends RuntimeException {

    public AmbiguousContestSuccessionException(String canonicalKey, String reason) {
        super("canonical 승계 본체를 정할 수 없습니다: " + canonicalKey + " (" + reason + ")");
    }
}

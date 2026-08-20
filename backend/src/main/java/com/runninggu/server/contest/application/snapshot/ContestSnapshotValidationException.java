package com.runninggu.server.contest.application.snapshot;

import java.util.List;

public class ContestSnapshotValidationException extends RuntimeException {

    private final List<String> errors;

    public ContestSnapshotValidationException(List<String> errors) {
        super("대회 snapshot 계약 검증 실패: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}

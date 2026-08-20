package com.runninggu.server.contest.application;

public record ContestSnapshotImportResult(
        Status status,
        int insertedContests,
        int updatedContests,
        int importedSources,
        int importedEvents) {

    public enum Status {
        APPLIED,
        NO_OP
    }

    public static ContestSnapshotImportResult noOp() {
        return new ContestSnapshotImportResult(Status.NO_OP, 0, 0, 0, 0);
    }
}

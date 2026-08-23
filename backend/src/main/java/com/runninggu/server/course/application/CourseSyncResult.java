package com.runninggu.server.course.application;

public record CourseSyncResult(
        boolean success,
        boolean skipped,
        int bundleCount,
        int ktoCount,
        int matchedCount,
        int gpxOnlyCount,
        int apiOnlyCount,
        int invalidFieldCount) {

    public static CourseSyncResult failed(int bundleCount) {
        return new CourseSyncResult(false, false, bundleCount, 0, 0, 0, 0, 0);
    }

    public static CourseSyncResult skipped(int bundleCount) {
        return new CourseSyncResult(false, true, bundleCount, 0, 0, 0, 0, 0);
    }
}

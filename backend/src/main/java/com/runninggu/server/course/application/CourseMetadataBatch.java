package com.runninggu.server.course.application;

import java.util.Map;

/** 전체 페이지를 성공적으로 읽은 한 번의 KTO 메타 snapshot이다. */
public record CourseMetadataBatch(
        Map<String, CourseMetadata> items,
        int rawCount,
        int invalidFieldCount) {

    public CourseMetadataBatch {
        items = Map.copyOf(items);
    }
}

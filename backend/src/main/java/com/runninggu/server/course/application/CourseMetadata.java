package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.CourseDifficulty;

/** KTO 한 항목에서 유효한 필드만 정규화한 최신 메타다. */
public record CourseMetadata(
        String courseId,
        String courseName,
        CourseDifficulty difficulty,
        String cycle,
        String summary) {}

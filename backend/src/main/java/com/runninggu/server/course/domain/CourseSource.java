package com.runninggu.server.course.domain;

import java.util.Objects;

/** 번들에 검증되어 기록된 코스 원천과 출처 문구다. (SPEC §4.11·§5.8) */
public record CourseSource(
        String key,
        String attribution,
        String license) {

    public CourseSource {
        Objects.requireNonNull(key);
        Objects.requireNonNull(attribution);
        Objects.requireNonNull(license);
    }
}

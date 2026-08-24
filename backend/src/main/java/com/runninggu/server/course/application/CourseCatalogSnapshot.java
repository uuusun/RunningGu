package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 목록·지역 집계·출처가 함께 교체되는 불변 catalog snapshot이다. */
public final class CourseCatalogSnapshot {

    private final List<CourseSource> sources;
    private final Map<String, CourseSource> sourceByKey;
    private final List<Course> courses;

    public CourseCatalogSnapshot(List<CourseSource> sources, List<Course> courses) {
        this.sources = List.copyOf(sources);
        this.courses = List.copyOf(courses);
        LinkedHashMap<String, CourseSource> byKey = new LinkedHashMap<>();
        for (CourseSource source : this.sources) {
            if (byKey.put(source.key(), source) != null) {
                throw new IllegalArgumentException("코스 원천 key가 중복됩니다.");
            }
        }
        this.sourceByKey = Map.copyOf(byKey);
        for (Course course : this.courses) {
            if (!sourceByKey.containsKey(course.source())) {
                throw new IllegalArgumentException("코스가 알 수 없는 원천을 참조합니다.");
            }
        }
    }

    public List<CourseSource> sources() {
        return sources;
    }

    public CourseSource source(String key) {
        return Objects.requireNonNull(sourceByKey.get(key));
    }

    public List<Course> courses() {
        return courses;
    }
}

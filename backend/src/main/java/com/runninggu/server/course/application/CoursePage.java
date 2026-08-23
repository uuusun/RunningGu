package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.Course;
import java.util.List;

public record CoursePage(
        List<Course> content,
        int number,
        int size,
        long totalElements,
        boolean hasNext,
        List<String> attributions) {

    public CoursePage {
        content = List.copyOf(content);
        attributions = List.copyOf(attributions);
    }
}

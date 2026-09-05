package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.Course;
import java.util.List;

/** 같은 catalog snapshot에서 읽은 전체 코스와 출처다. (SPEC §5.8·결정-44) */
public record CourseDetail(Course course, List<String> attributions) {
    public CourseDetail {
        attributions = List.copyOf(attributions);
    }
}

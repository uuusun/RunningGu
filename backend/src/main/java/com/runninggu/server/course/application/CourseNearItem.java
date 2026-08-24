package com.runninggu.server.course.application;

import java.math.BigDecimal;

public sealed interface CourseNearItem permits CuratedCourseRoute, OsmGeneratedRoute, WalkingSpot {

    String name();

    int distanceM();

    BigDecimal lat();

    BigDecimal lng();
}

package com.runninggu.server.course.application;

import java.math.BigDecimal;

public record WalkingSpot(
        String name,
        int distanceM,
        BigDecimal lat,
        BigDecimal lng,
        String category,
        String address,
        String placeUrl) implements CourseNearItem {}

package com.runninggu.server.course.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.course.catalog")
public record CourseCatalogProperties(
        String bundleResource,
        int minimumCourseCount) {}

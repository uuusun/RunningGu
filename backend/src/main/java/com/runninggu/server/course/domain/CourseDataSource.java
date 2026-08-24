package com.runninggu.server.course.domain;

/** 큐레이션 코스의 메타·경로 결합 상태다. (SPEC §5.8·§8.4) */
public enum CourseDataSource {
    API_GPX,
    GPX_ONLY,
    OSM_GENERATED
}

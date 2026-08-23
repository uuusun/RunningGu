package com.runninggu.server.savedcourse.application;

import com.runninggu.server.savedcourse.domain.CourseDataSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** 저장 당시 서버가 알고 있는 검증 완료 원천 문구를 snapshot으로 확정한다. (SPEC 결정-44) */
@Component
public class SavedCourseAttributionResolver {

    private static final String DURUNUBI_ATTRIBUTION = "두루누비 걷기길(한국관광공사)";
    private static final String OSM_ATTRIBUTION = "© OpenStreetMap contributors";

    public List<String> resolve(CourseDataSource dataSource) {
        return switch (dataSource) {
            case API_GPX, GPX_ONLY -> List.of(DURUNUBI_ATTRIBUTION);
            case OSM_GENERATED -> List.of(OSM_ATTRIBUTION);
        };
    }
}

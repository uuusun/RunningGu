package com.runninggu.server.itinerary.domain;

import com.runninggu.server.poi.domain.PoiCategory;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 무상태 생성 결과다. sources는 서버 내부 추적값이며 API 응답에서 제외한다. (결정-53) */
public record GeneratedItinerary(
        String title,
        ItineraryPlan plan,
        GeneratedRecovery recovery,
        List<GeneratedDay> days,
        Map<PoiCategory, String> sources) {

    public GeneratedItinerary {
        title = Objects.requireNonNull(title);
        plan = Objects.requireNonNull(plan);
        days = List.copyOf(days);
        sources = Map.copyOf(sources);
    }
}

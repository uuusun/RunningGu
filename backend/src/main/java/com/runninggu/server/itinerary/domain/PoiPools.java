package com.runninggu.server.itinerary.domain;

import com.runninggu.server.poi.domain.PoiCategory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 카테고리별 장소 풀과 정상 완료된 LIVE 원천을 묶는다. sources는 HTTP에 노출하지 않는다. */
public record PoiPools(
        Map<PoiCategory, List<ItineraryPlace>> places,
        Map<PoiCategory, String> sources) {

    public PoiPools {
        LinkedHashMap<PoiCategory, List<ItineraryPlace>> placeCopy = new LinkedHashMap<>();
        places.forEach((category, items) -> placeCopy.put(category, List.copyOf(items)));
        places = Map.copyOf(placeCopy);
        sources = Map.copyOf(new LinkedHashMap<>(sources));
    }

    public List<ItineraryPlace> get(PoiCategory category) {
        return places.getOrDefault(category, List.of());
    }
}

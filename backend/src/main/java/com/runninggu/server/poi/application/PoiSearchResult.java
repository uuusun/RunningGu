package com.runninggu.server.poi.application;

import com.runninggu.server.poi.domain.Poi;
import java.util.List;

public record PoiSearchResult(List<Poi> items) {

    public PoiSearchResult {
        items = List.copyOf(items);
    }
}

package com.runninggu.server.poi.application;

import com.runninggu.server.poi.domain.Poi;
import java.util.List;

public interface KtoPoiSource {

    List<Poi> search(PoiSearchCriteria criteria, int limit);
}

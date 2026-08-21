package com.runninggu.server.poi.application;

import com.runninggu.server.poi.domain.Poi;
import java.util.List;

/** AP-23 두루누비 동기화가 제공할 NATURE 후보 연결부다. */
public interface NaturePoiBundleSource {

    List<Poi> search(PoiSearchCriteria criteria, int limit);
}

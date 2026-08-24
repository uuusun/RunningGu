package com.runninggu.server.course.application;

import java.math.BigDecimal;

public interface OsmRouteGenerator {

    OsmRouteSearchResult generate(BigDecimal lat, BigDecimal lng, BigDecimal targetKm);
}

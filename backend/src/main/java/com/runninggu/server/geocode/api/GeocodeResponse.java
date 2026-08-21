package com.runninggu.server.geocode.api;

import com.runninggu.server.geocode.domain.GeocodeResult;
import java.math.BigDecimal;

public record GeocodeResponse(
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng) {

    public static GeocodeResponse from(GeocodeResult result) {
        return new GeocodeResponse(
                result.name(),
                result.address(),
                result.lat(),
                result.lng());
    }
}

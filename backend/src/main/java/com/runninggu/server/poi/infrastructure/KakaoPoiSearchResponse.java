package com.runninggu.server.poi.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

record KakaoPoiSearchResponse(
        Meta meta,
        List<Document> documents) {

    record Meta(@JsonProperty("is_end") Boolean isEnd) {}

    record Document(
            @JsonProperty("place_name") String placeName,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            @JsonProperty("place_url") String placeUrl,
            String x,
            String y,
            String distance) {}
}

package com.runninggu.server.geocode.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

record KakaoKeywordSearchResponse(List<Document> documents) {

    record Document(
            @JsonProperty("place_name") String placeName,
            @JsonProperty("road_address_name") String roadAddressName,
            @JsonProperty("address_name") String addressName,
            String x,
            String y) {}
}

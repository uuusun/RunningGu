package com.runninggu.server.auth.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoAccessTokenInfoResponse(
        Long id,
        @JsonProperty("app_id") Long appId) {}

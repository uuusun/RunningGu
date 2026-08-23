package com.runninggu.server.itinerary.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 사용자가 선택한 숙소의 생성 요청 snapshot이다. 주소는 서버에 보내지 않는다. */
public record ItineraryHotelRequest(
        @NotBlank @Schema(example = "호텔 세종 가온") String name,
        @NotNull
                @DecimalMin("-90")
                @DecimalMax("90")
                @Schema(example = "36.4901", minimum = "-90", maximum = "90")
                BigDecimal lat,
        @NotNull
                @DecimalMin("-180")
                @DecimalMax("180")
                @Schema(example = "127.2688", minimum = "-180", maximum = "180")
                BigDecimal lng) {}

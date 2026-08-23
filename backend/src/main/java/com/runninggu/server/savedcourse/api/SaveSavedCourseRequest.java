package com.runninggu.server.savedcourse.api;

import com.runninggu.server.savedcourse.application.SaveSavedCourseCommand;
import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record SaveSavedCourseRequest(
        @Size(max = 255) String sourceCourseId,
        @NotNull CourseDataSource dataSource,
        @NotBlank @Size(max = 255) String courseName,
        @Size(max = 32) String region,
        @NotNull @DecimalMin("0.001") @Digits(integer = 5, fraction = 3) BigDecimal distanceKm,
        @NotNull @Min(1) Integer durationMin,
        CourseDifficulty difficulty,
        @NotNull @Min(0) Integer gainM,
        @NotNull @Size(max = 100) List<@NotNull Integer> elevationProfileM,
        @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 7)
                BigDecimal entryLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7)
                BigDecimal entryLng,
        @NotBlank String pathPolyline) {

    public SaveSavedCourseCommand toCommand() {
        return new SaveSavedCourseCommand(
                sourceCourseId,
                dataSource,
                courseName,
                region,
                distanceKm,
                durationMin,
                difficulty,
                gainM,
                elevationProfileM == null ? null : List.copyOf(elevationProfileM),
                entryLat,
                entryLng,
                pathPolyline);
    }
}

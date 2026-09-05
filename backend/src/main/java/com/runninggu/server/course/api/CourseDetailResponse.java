package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CourseDetail;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CourseElevationProfile;
import com.runninggu.server.course.domain.E5PolylineEncoder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 지역 목록 필드와 원본 전체 geometry를 반환한다. (SPEC §5.8·API 명세 §6-4) */
public record CourseDetailResponse(
        String courseId,
        String courseName,
        String sido,
        String sigun,
        BigDecimal distanceKm,
        CourseDifficulty difficulty,
        int gainM,
        int durationMin,
        CourseDataSource dataSource,
        @Schema(nullable = true) Instant syncedAt,
        @Schema(description = "원본 전체 points 순서의 2D Google Encoded Polyline E5")
        String pathPolyline,
        @Schema(description = "전체 points를 순서 보존·최대 100개로 균등 축약한 정수 미터 고도")
        List<Integer> elevationProfileM,
        List<String> attributions) {

    static CourseDetailResponse from(CourseDetail detail) {
        CourseItemResponse item = CourseItemResponse.from(detail.course());
        return new CourseDetailResponse(
                item.courseId(), item.courseName(), item.sido(), item.sigun(),
                item.distanceKm(), item.difficulty(), item.gainM(), item.durationMin(),
                item.dataSource(), item.syncedAt(),
                new E5PolylineEncoder().encode(detail.course().points()),
                CourseElevationProfile.sample(detail.course().points()),
                detail.attributions());
    }
}

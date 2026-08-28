package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CourseCatalog;
import com.runninggu.server.course.application.CourseNearService;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Course", description = "두루누비 기반 지역별 러닝·걷기 코스")
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseCatalog catalog;
    private final CourseNearService nearService;

    public CourseController(CourseCatalog catalog, CourseNearService nearService) {
        this.catalog = catalog;
        this.nearService = nearService;
    }

    @Operation(summary = "출발지 주변 경로·장소 통합 목록 조회")
    @GetMapping("/near")
    public CourseNearResponse near(
            @Parameter(description = "사용자가 검색·프리셋·S7 숙소에서 고른 출발지 위도")
                    @RequestParam
                    BigDecimal lat,
            @Parameter(description = "사용자가 검색·프리셋·S7 숙소에서 고른 출발지 경도")
                    @RequestParam
                    BigDecimal lng,
            @RequestParam(defaultValue = "5") BigDecimal targetKm,
            @RequestParam(defaultValue = "8") BigDecimal radiusKm,
            @RequestParam(defaultValue = "12") int size) {
        return CourseNearResponse.from(
                nearService.find(lat, lng, targetKm, radiusKm, size));
    }

    @Operation(summary = "지역별 코스 목록 조회")
    @GetMapping
    public CourseListResponse list(
            @Parameter(description = "NFC·앞뒤 공백 제거 후 시도 단축명과 정확히 일치")
                    @RequestParam(required = false)
                    String region,
            @Parameter(
                            description = "0부터 시작하는 페이지",
                            schema = @Schema(defaultValue = "0", minimum = "0"))
                    @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(
                            description = "페이지 크기, 1~50",
                            schema = @Schema(defaultValue = "20", minimum = "1", maximum = "50"))
                    @RequestParam(defaultValue = "20")
                    int size) {
        return CourseListResponse.from(catalog.find(region, page, size));
    }

    @Operation(summary = "코스 지역별 건수 조회")
    @GetMapping("/regions")
    public CourseRegionsResponse regions() {
        return CourseRegionsResponse.from(catalog.regions());
    }
}

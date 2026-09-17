package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CourseCatalog;
import com.runninggu.server.course.application.CourseLoopService;
import com.runninggu.server.course.application.CourseNearService;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Course", description = "두루누비 기반 지역별 러닝·걷기 코스")
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseCatalog catalog;
    private final CourseNearService nearService;
    private final CourseLoopService loopService;

    public CourseController(
            CourseCatalog catalog,
            CourseNearService nearService,
            CourseLoopService loopService) {
        this.catalog = catalog;
        this.nearService = nearService;
        this.loopService = loopService;
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

    @Operation(summary = "걷기 스팟 진입점 순환 경로 생성")
    @GetMapping("/loop")
    public CourseLoopResponse loop(
            @Parameter(description = "화면에서 선택한 출발지 위도")
                    @RequestParam
                    BigDecimal lat,
            @Parameter(description = "화면에서 선택한 출발지 경도")
                    @RequestParam
                    BigDecimal lng,
            @Parameter(description = "순환 경로 진입점 위도")
                    @RequestParam
                    BigDecimal entryLat,
            @Parameter(description = "순환 경로 진입점 경도")
                    @RequestParam
                    BigDecimal entryLng,
            @Parameter(description = "목표 거리(km), 1~21 범위의 0.5 단위")
                    @RequestParam
                    BigDecimal targetKm,
            @Parameter(description = "경로 이름에만 사용하는 걷기 스팟 이름")
                    @RequestParam(required = false)
                    String entryName) {
        return CourseLoopResponse.from(
                loopService.find(lat, lng, entryLat, entryLng, targetKm, entryName));
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

    @Operation(summary = "큐레이션 코스 전체 상세 조회",
            description = "게스트 허용. 원본 전체 경로를 반환하며 현재 snapshot에 없는 ID는 404 COURSE_NOT_FOUND다.")
    @GetMapping("/{courseId}")
    public CourseDetailResponse detail(@PathVariable String courseId) {
        return CourseDetailResponse.from(catalog.detail(courseId));
    }

    @Operation(summary = "코스 지역별 건수 조회")
    @GetMapping("/regions")
    public CourseRegionsResponse regions() {
        return CourseRegionsResponse.from(catalog.regions());
    }
}

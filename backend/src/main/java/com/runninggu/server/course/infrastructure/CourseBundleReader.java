package com.runninggu.server.course.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.course.application.CourseCatalogSnapshot;
import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CoursePoint;
import com.runninggu.server.course.domain.CourseSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/** classpath 코스 번들을 읽고 계약 전체를 검증한 뒤 불변 snapshot으로 바꾼다. */
public final class CourseBundleReader {

    private final ObjectMapper objectMapper;
    private final Resource resource;
    private final CourseBundleValidator validator;

    public CourseBundleReader(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            CourseCatalogProperties properties) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.resource = resourceLoader.getResource(properties.bundleResource());
        this.validator = new CourseBundleValidator(properties.minimumCourseCount());
    }

    public CourseCatalogSnapshot read() {
        if (!resource.exists()) {
            throw new CourseBundleValidationException("코스 번들을 찾을 수 없습니다: " + resource.getDescription());
        }
        try (InputStream inputStream = resource.getInputStream()) {
            CourseBundleFile bundle = objectMapper.readValue(inputStream, CourseBundleFile.class);
            validator.validate(bundle);
            return toSnapshot(bundle);
        } catch (CourseBundleValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new CourseBundleValidationException("코스 번들을 읽거나 해석하지 못했습니다.", exception);
        }
    }

    private CourseCatalogSnapshot toSnapshot(CourseBundleFile bundle) {
        List<CourseSource> sources = bundle.sources().stream()
                .map(source -> new CourseSource(
                        source.key(),
                        source.attribution(),
                        source.license()))
                .toList();
        List<Course> courses = bundle.courses().stream()
                .map(course -> new Course(
                        course.courseId(),
                        course.source(),
                        course.dataSource(),
                        course.courseName(),
                        course.sido(),
                        course.sigun(),
                        course.distanceKm(),
                        course.gainM(),
                        course.difficulty(),
                        course.cycle(),
                        course.summary(),
                        toPoints(course.points()),
                        null))
                .toList();
        return new CourseCatalogSnapshot(sources, courses);
    }

    private List<CoursePoint> toPoints(List<List<java.math.BigDecimal>> points) {
        return points.stream()
                .map(point -> new CoursePoint(
                        point.get(0), point.get(1), point.get(2), point.get(3)))
                .toList();
    }
}

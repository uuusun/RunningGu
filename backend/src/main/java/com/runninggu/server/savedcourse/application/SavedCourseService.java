package com.runninggu.server.savedcourse.application;

import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.savedcourse.application.SavedCourseViews.Details;
import com.runninggu.server.savedcourse.application.SavedCourseViews.PageResult;
import com.runninggu.server.savedcourse.application.SavedCourseViews.Saved;
import com.runninggu.server.savedcourse.application.SavedCourseViews.Summary;
import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.InvalidCoursePolylineException;
import com.runninggu.server.savedcourse.domain.RouteFingerprintGenerator;
import com.runninggu.server.savedcourse.domain.SavedCourse;
import com.runninggu.server.savedcourse.infrastructure.SavedCourseRepository;
import com.runninggu.server.savedcourse.infrastructure.SavedCourseStore;
import com.runninggu.server.savedcourse.infrastructure.SavedCourseSummaryRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장 코스 snapshot의 멱등 저장·조회·소유권을 처리한다. (API 명세 §7-A) */
@Service
@Transactional(readOnly = true)
public class SavedCourseService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    private final SavedCourseRepository repository;
    private final SavedCourseStore store;
    private final AppUserRepository userRepository;
    private final SavedCourseAttributionResolver attributionResolver;
    private final RouteFingerprintGenerator fingerprintGenerator;
    private final Clock clock;

    public SavedCourseService(
            SavedCourseRepository repository,
            SavedCourseStore store,
            AppUserRepository userRepository,
            SavedCourseAttributionResolver attributionResolver,
            Clock clock) {
        this.repository = repository;
        this.store = store;
        this.userRepository = userRepository;
        this.attributionResolver = attributionResolver;
        this.fingerprintGenerator = new RouteFingerprintGenerator();
        this.clock = clock;
    }

    @Transactional
    public Saved save(long userId, SaveSavedCourseCommand command) {
        requireUser(userId);
        validate(command);
        String routeFingerprint;
        try {
            routeFingerprint = fingerprintGenerator.generate(command.pathPolyline());
        } catch (InvalidCoursePolylineException exception) {
            throw validation(exception.getMessage());
        }
        SavedCourseStore.StoreResult result = store.insertOrFind(
                userId,
                routeFingerprint,
                command,
                attributionResolver.resolve(command.dataSource()),
                clock.instant());
        return new Saved(result.id(), result.created());
    }

    public PageResult list(long userId, int pageNumber, int size) {
        validatePage(pageNumber, size);
        requireUser(userId);
        Page<SavedCourseSummaryRow> page = repository.findSummariesByUserId(
                userId,
                PageRequest.of(pageNumber, size));
        return new PageResult(
                page.getContent().stream().map(this::summary).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext());
    }

    public Details details(long userId, long id) {
        requireUser(userId);
        SavedCourse course = requireOwned(userId, id);
        return new Details(
                course.getId(),
                course.getCourseName(),
                course.getDistanceKm(),
                course.getDurationMin(),
                course.getGainM(),
                course.getDifficulty(),
                course.getDataSource(),
                course.getRegion(),
                course.getSavedAt(),
                course.getElevationProfileM(),
                course.getPathPolyline(),
                course.getAttributions());
    }

    @Transactional
    public void delete(long userId, long id) {
        requireUser(userId);
        repository.delete(requireOwned(userId, id));
    }

    private Summary summary(SavedCourseSummaryRow row) {
        return new Summary(
                row.id(),
                row.courseName(),
                row.distanceKm(),
                row.durationMin(),
                row.gainM(),
                row.difficulty(),
                row.dataSource(),
                row.region(),
                row.savedAt());
    }

    private SavedCourse requireOwned(long userId, long id) {
        SavedCourse course = repository.findById(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.SAVED_COURSE_NOT_FOUND,
                        "저장 코스 ID " + id + "를 찾을 수 없습니다."));
        if (!course.getUser().getId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "다른 사용자의 저장 코스에는 접근할 수 없습니다.");
        }
        return course;
    }

    private void requireUser(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
        }
    }

    private void validate(SaveSavedCourseCommand command) {
        if (command == null
                || command.dataSource() == null
                || isBlank(command.courseName())
                || command.distanceKm() == null
                || command.distanceKm().signum() <= 0
                || command.distanceKm().precision() - command.distanceKm().scale() > 5
                || Math.max(command.distanceKm().scale(), 0) > 3
                || command.durationMin() <= 0
                || command.gainM() < 0
                || command.elevationProfileM() == null
                || command.elevationProfileM().size() > 100
                || command.elevationProfileM().stream().anyMatch(value -> value == null)
                || !inRange(command.entryLat(), MIN_LATITUDE, MAX_LATITUDE)
                || !inRange(command.entryLng(), MIN_LONGITUDE, MAX_LONGITUDE)
                || isBlank(command.pathPolyline())) {
            throw validation("저장 코스 요청 값을 확인해 주세요.");
        }

        boolean osm = command.dataSource() == CourseDataSource.OSM_GENERATED;
        if (osm && (command.sourceCourseId() != null || command.region() != null)) {
            throw validation("OSM_GENERATED에는 sourceCourseId와 region을 보낼 수 없습니다.");
        }
        if (!osm && (isBlank(command.sourceCourseId()) || isBlank(command.region()))) {
            throw validation("큐레이션 코스에는 sourceCourseId와 region이 필요합니다.");
        }
    }

    private boolean inRange(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value != null
                && value.compareTo(minimum) >= 0
                && value.compareTo(maximum) <= 0
                && Math.max(value.scale(), 0) <= 7;
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("page는 0 이상, size는 1 이상 50 이하여야 합니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}

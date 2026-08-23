package com.runninggu.server.savedcourse.domain;

import com.runninggu.server.auth.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사용자가 저장한 코스 geometry와 표시 메타데이터 snapshot이다. (SPEC §6.4) */
@Entity
@Table(
        name = "saved_course",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_saved_course_user_fingerprint",
                columnNames = {"user_id", "route_fingerprint"}))
public class SavedCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "route_fingerprint", nullable = false, length = 67)
    private String routeFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 32)
    private CourseDataSource dataSource;

    @Column(name = "source_course_id", length = 255)
    private String sourceCourseId;

    @Column(name = "course_name", nullable = false, length = 255)
    private String courseName;

    @Column(length = 32)
    private String region;

    @Column(name = "distance_km", nullable = false, precision = 8, scale = 3)
    private BigDecimal distanceKm;

    @Column(name = "duration_min", nullable = false)
    private int durationMin;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CourseDifficulty difficulty;

    @Column(name = "gain_m", nullable = false)
    private int gainM;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "elevation_profile_m", nullable = false, columnDefinition = "jsonb")
    private List<Integer> elevationProfileM = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> attributions = List.of();

    @Column(name = "entry_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal entryLat;

    @Column(name = "entry_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal entryLng;

    @Column(name = "path_polyline", nullable = false, columnDefinition = "text")
    private String pathPolyline;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    protected SavedCourse() {}

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public CourseDataSource getDataSource() {
        return dataSource;
    }

    public String getSourceCourseId() {
        return sourceCourseId;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getRegion() {
        return region;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public CourseDifficulty getDifficulty() {
        return difficulty;
    }

    public int getGainM() {
        return gainM;
    }

    public List<Integer> getElevationProfileM() {
        return List.copyOf(elevationProfileM);
    }

    public List<String> getAttributions() {
        return List.copyOf(attributions);
    }

    public BigDecimal getEntryLat() {
        return entryLat;
    }

    public BigDecimal getEntryLng() {
        return entryLng;
    }

    public String getPathPolyline() {
        return pathPolyline;
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}

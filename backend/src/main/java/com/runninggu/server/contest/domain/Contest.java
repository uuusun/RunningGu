package com.runninggu.server.contest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "contest")
public class Contest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canonical_key", nullable = false, unique = true, length = 255)
    private String canonicalKey;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 32)
    private String region;

    @Column(nullable = false, length = 255)
    private String place;

    @Column(name = "road_address", length = 255)
    private String roadAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    @Column(name = "contest_date", nullable = false)
    private LocalDate contestDate;

    /** 대회 시작 시각은 timezone 변환을 하지 않는 KST 벽시계 값이다. (SPEC §6.6) */
    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_time")
    private LocalTime startTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_status", length = 32)
    private ContestRegistrationStatus sourceStatus;

    @Column(name = "apply_start")
    private LocalDate applyStart;

    @Column(name = "apply_end")
    private LocalDate applyEnd;

    @Column(length = 255)
    private String organizer;

    @Column(name = "official_url", length = 2048)
    private String officialUrl;

    @Column(name = "detail_url", length = 2048)
    private String detailUrl;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContestCategory category;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Contest() {}

    public static Contest create(String canonicalKey, Instant updatedAt) {
        Contest contest = new Contest();
        contest.canonicalKey = canonicalKey;
        contest.active = true;
        contest.updatedAt = updatedAt;
        return contest;
    }

    /** 서버용 snapshot의 canonical 값을 전부 교체한다. (SPEC §8.2, 결정-40·47) */
    public void update(
            String canonicalKey,
            String name,
            String region,
            String place,
            String roadAddress,
            BigDecimal lat,
            BigDecimal lng,
            LocalDate contestDate,
            LocalTime startTime,
            ContestRegistrationStatus sourceStatus,
            LocalDate applyStart,
            LocalDate applyEnd,
            String organizer,
            String officialUrl,
            String detailUrl,
            String imageUrl,
            ContestCategory category,
            Instant checkedAt,
            Instant updatedAt) {
        this.canonicalKey = canonicalKey;
        this.name = name;
        this.region = region;
        this.place = place;
        this.roadAddress = roadAddress;
        this.lat = lat;
        this.lng = lng;
        this.contestDate = contestDate;
        this.startTime = startTime;
        this.sourceStatus = sourceStatus;
        this.applyStart = applyStart;
        this.applyEnd = applyEnd;
        this.organizer = organizer;
        this.officialUrl = officialUrl;
        this.detailUrl = detailUrl;
        this.imageUrl = imageUrl;
        this.category = category;
        this.checkedAt = checkedAt;
        this.updatedAt = updatedAt;
    }

    public void updateActive(boolean active, Instant updatedAt) {
        if (this.active != active) {
            this.active = active;
            this.updatedAt = updatedAt;
        }
    }

    public Long getId() {
        return id;
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    public String getName() {
        return name;
    }

    public String getRegion() {
        return region;
    }

    public String getPlace() {
        return place;
    }

    public String getRoadAddress() {
        return roadAddress;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public LocalDate getContestDate() {
        return contestDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public ContestRegistrationStatus getSourceStatus() {
        return sourceStatus;
    }

    public LocalDate getApplyStart() {
        return applyStart;
    }

    public LocalDate getApplyEnd() {
        return applyEnd;
    }

    public String getOrganizer() {
        return organizer;
    }

    public String getOfficialUrl() {
        return officialUrl;
    }

    public String getDetailUrl() {
        return detailUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}

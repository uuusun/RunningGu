package com.runninggu.server.itinerary.domain;

import com.runninggu.server.auth.domain.AppUser;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.poi.domain.PoiCategory;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 저장 당시 동선 snapshot의 aggregate root다. (SPEC §6.3, 결정-45) */
@Entity
@Table(
        name = "itinerary",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_itinerary_trip",
                columnNames = {"user_id", "contest_id", "start_date", "end_date"}))
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContestEventType event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<PoiCategory> themes = List.of();

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "hotel_name", length = 255)
    private String hotelName;

    @Column(name = "hotel_lat", precision = 10, scale = 7)
    private BigDecimal hotelLat;

    @Column(name = "hotel_lng", precision = 10, scale = 7)
    private BigDecimal hotelLng;

    @Column(name = "region_snapshot", nullable = false, length = 32)
    private String regionSnapshot;

    @Column(name = "recovery_label", length = 255)
    private String recoveryLabel;

    @Column(name = "recovery_note")
    private String recoveryNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(
            mappedBy = "itinerary",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<ItineraryDay> days = new ArrayList<>();

    protected Itinerary() {}

    public static Itinerary create(
            AppUser user,
            Contest contest,
            ItinerarySnapshot snapshot,
            Instant now) {
        Itinerary itinerary = new Itinerary();
        itinerary.user = Objects.requireNonNull(user);
        itinerary.contest = Objects.requireNonNull(contest);
        itinerary.createdAt = Objects.requireNonNull(now);
        itinerary.replace(snapshot, now);
        return itinerary;
    }

    public void replace(ItinerarySnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot);
        title = snapshot.title();
        event = snapshot.event();
        themes = List.copyOf(snapshot.themes());
        startDate = snapshot.startDate();
        endDate = snapshot.endDate();
        hotelName = snapshot.hotelName();
        hotelLat = snapshot.hotelLat();
        hotelLng = snapshot.hotelLng();
        regionSnapshot = snapshot.regionSnapshot();
        recoveryLabel = snapshot.recoveryLabel();
        recoveryNote = snapshot.recoveryNote();
        updatedAt = Objects.requireNonNull(now);
        days.clear();
        snapshot.days().forEach(day -> days.add(ItineraryDay.create(this, day)));
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Contest getContest() {
        return contest;
    }

    public String getTitle() {
        return title;
    }

    public ContestEventType getEvent() {
        return event;
    }

    public List<PoiCategory> getThemes() {
        return List.copyOf(themes);
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getHotelName() {
        return hotelName;
    }

    public BigDecimal getHotelLat() {
        return hotelLat;
    }

    public BigDecimal getHotelLng() {
        return hotelLng;
    }

    public String getRegionSnapshot() {
        return regionSnapshot;
    }

    public String getRecoveryLabel() {
        return recoveryLabel;
    }

    public String getRecoveryNote() {
        return recoveryNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ItineraryDay> getDays() {
        return days;
    }
}

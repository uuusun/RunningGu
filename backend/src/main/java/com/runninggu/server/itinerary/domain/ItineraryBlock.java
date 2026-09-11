package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.Contest;
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
import java.time.LocalTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "itinerary_block",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_itinerary_block_day_order",
                columnNames = {"day_id", "order_no"}))
public class ItineraryBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "day_id", nullable = false)
    private ItineraryDay day;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id")
    private Contest contest;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 32)
    private BlockType blockType;

    @Column(name = "system_managed", nullable = false)
    private boolean systemManaged;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BlockCategory category;

    @Column(name = "place_name", length = 255)
    private String placeName;

    @Column(length = 255)
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    @Column
    private String description;

    protected ItineraryBlock() {}

    static ItineraryBlock create(ItineraryDay day, ItineraryBlockSnapshot snapshot) {
        ItineraryBlock block = new ItineraryBlock();
        block.day = day;
        block.contest = snapshot.contest();
        block.blockType = snapshot.blockType();
        block.systemManaged = snapshot.systemManaged();
        block.orderNo = snapshot.orderNo();
        block.startTime = snapshot.startTime();
        block.title = snapshot.title();
        block.category = snapshot.category();
        block.placeName = normalizeNullable(snapshot.placeName());
        block.address = normalizeNullable(snapshot.address());
        block.lat = snapshot.lat();
        block.lng = snapshot.lng();
        block.description = normalizeNullable(snapshot.description());
        return block;
    }

    static ItineraryBlock createUser(
            ItineraryDay day,
            int orderNo,
            ItineraryBlockDraft draft) {
        return create(day, new ItineraryBlockSnapshot(
                null,
                BlockType.USER,
                orderNo,
                draft.startTime(),
                draft.title(),
                draft.category(),
                draft.placeName(),
                draft.address(),
                draft.lat(),
                draft.lng(),
                draft.description()));
    }

    public void update(ItineraryBlockDraft draft) {
        startTime = draft.startTime();
        title = draft.title();
        category = draft.category();
        placeName = normalizeNullable(draft.placeName());
        address = normalizeNullable(draft.address());
        lat = draft.lat();
        lng = draft.lng();
        description = normalizeNullable(draft.description());
    }

    void changeOrder(int orderNo) {
        this.orderNo = orderNo;
    }

    public Long getId() {
        return id;
    }

    public ItineraryDay getDay() {
        return day;
    }

    public BlockType getBlockType() {
        return blockType;
    }

    public boolean isSystemManaged() {
        return systemManaged;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public String getTitle() {
        return title;
    }

    public BlockCategory getCategory() {
        return category;
    }

    public String getPlaceName() {
        return placeName;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public String getDescription() {
        return description;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

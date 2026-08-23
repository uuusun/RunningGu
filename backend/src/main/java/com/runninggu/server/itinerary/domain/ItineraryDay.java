package com.runninggu.server.itinerary.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(
        name = "itinerary_day",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_itinerary_day_index",
                columnNames = {"itinerary_id", "day_index"}))
public class ItineraryDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(name = "day_index", nullable = false)
    private int dayIndex;

    @Column(name = "day_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private boolean recovery;

    @Column
    private String note;

    @OneToMany(
            mappedBy = "day",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("orderNo ASC")
    private List<ItineraryBlock> blocks = new ArrayList<>();

    protected ItineraryDay() {}

    static ItineraryDay create(Itinerary itinerary, ItineraryDaySnapshot snapshot) {
        ItineraryDay day = new ItineraryDay();
        day.itinerary = itinerary;
        day.dayIndex = snapshot.dayIndex();
        day.date = snapshot.date();
        day.recovery = snapshot.recovery();
        day.note = normalizeNullable(snapshot.note());
        snapshot.blocks().forEach(block -> day.blocks.add(ItineraryBlock.create(day, block)));
        return day;
    }

    public ItineraryBlock addUserBlock(ItineraryBlockDraft draft) {
        int nextOrder = blocks.stream()
                .mapToInt(ItineraryBlock::getOrderNo)
                .max()
                .orElse(-1) + 1;
        ItineraryBlock block = ItineraryBlock.createUser(this, nextOrder, draft);
        blocks.add(block);
        return block;
    }

    /** USER 전체 집합만 순서를 바꾸며 RACE의 orderNo는 그대로 둔다. (API G-2) */
    public boolean reorderUserBlocks(List<Long> blockIds) {
        List<ItineraryBlock> userBlocks = blocks.stream()
                .filter(block -> block.getBlockType() == BlockType.USER)
                .toList();
        Set<Long> expected = new HashSet<>(userBlocks.stream()
                .map(ItineraryBlock::getId)
                .toList());
        if (blockIds.size() != expected.size()
                || new HashSet<>(blockIds).size() != blockIds.size()
                || !expected.equals(new HashSet<>(blockIds))) {
            return false;
        }

        List<Integer> userSlots = userBlocks.stream()
                .map(ItineraryBlock::getOrderNo)
                .sorted()
                .toList();
        for (int index = 0; index < blockIds.size(); index++) {
            Long blockId = blockIds.get(index);
            ItineraryBlock block = userBlocks.stream()
                    .filter(candidate -> candidate.getId().equals(blockId))
                    .findFirst()
                    .orElseThrow();
            block.changeOrder(userSlots.get(index));
        }
        blocks.sort(Comparator.comparingInt(ItineraryBlock::getOrderNo));
        return true;
    }

    public void remove(ItineraryBlock block) {
        blocks.remove(block);
    }

    public Long getId() {
        return id;
    }

    public Itinerary getItinerary() {
        return itinerary;
    }

    public int getDayIndex() {
        return dayIndex;
    }

    public LocalDate getDate() {
        return date;
    }

    public boolean isRecovery() {
        return recovery;
    }

    public String getNote() {
        return note;
    }

    public List<ItineraryBlock> getBlocks() {
        return blocks;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

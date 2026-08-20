package com.runninggu.server.contest.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "contest_source")
public class ContestSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private ContestSourceType sourceType;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "raw_status", length = 32)
    private String rawStatus;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "consecutive_missing_count", nullable = false)
    private int consecutiveMissingCount;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode rawPayload;

    protected ContestSource() {}

    public static ContestSource create(ContestSourceType sourceType, String externalId) {
        ContestSource source = new ContestSource();
        source.sourceType = sourceType;
        source.externalId = externalId;
        return source;
    }

    /** 승인 snapshot에 재등장한 원천은 즉시 활성화하고 누락 횟수를 초기화한다. (SPEC §8.2) */
    public void applySnapshot(
            Contest contest,
            String sourceUrl,
            String rawStatus,
            Instant fetchedAt,
            JsonNode rawPayload) {
        this.contest = contest;
        this.sourceUrl = sourceUrl;
        this.rawStatus = rawStatus;
        this.fetchedAt = fetchedAt;
        this.rawPayload = rawPayload.deepCopy();
        this.active = true;
        this.consecutiveMissingCount = 0;
    }

    /** 서로 다른 승인 full snapshot에서만 호출한다. (SPEC §8.2, 결정-46) */
    public void markMissing() {
        consecutiveMissingCount = Math.min(2, consecutiveMissingCount + 1);
        active = consecutiveMissingCount < 2;
    }

    public Long getId() {
        return id;
    }

    public Contest getContest() {
        return contest;
    }

    public ContestSourceType getSourceType() {
        return sourceType;
    }

    public String getExternalId() {
        return externalId;
    }

    public boolean isActive() {
        return active;
    }

    public int getConsecutiveMissingCount() {
        return consecutiveMissingCount;
    }
}

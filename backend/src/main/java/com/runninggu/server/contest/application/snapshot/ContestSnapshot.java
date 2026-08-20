package com.runninggu.server.contest.application.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContestSnapshot(Integer schemaVersion, Meta meta, List<ContestItem> contests) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            String source,
            String sourceSha256,
            Integer sourceRowCount,
            Integer canonicalCount,
            Integer sourceRecordCount,
            Integer eventRecordCount,
            List<SkippedSource> skipped,
            String checkedAtMax) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkippedSource(String externalId, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContestItem(
            String canonicalKey,
            String name,
            String region,
            String place,
            String roadAddress,
            String contestDate,
            String startTime,
            List<String> events,
            String category,
            String applyStart,
            String applyEnd,
            String regStatusFallback,
            String organizer,
            String officialUrl,
            String detailUrl,
            String imageUrl,
            BigDecimal lat,
            BigDecimal lng,
            String checkedAt,
            List<Source> sources) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(
            String sourceType,
            String externalId,
            String sourceUrl,
            String fetchedAt,
            String lastCheckedDate,
            JsonNode rawPayload) {}
}

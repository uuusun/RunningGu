package com.runninggu.server.contest.application.snapshot;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Python 생산물과 Java 소비자 사이의 파일 계약을 DB 변경 전에 전부 검증한다. (SPEC §8.2) */
@Component
public class ContestSnapshotValidator {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final Set<String> REGIONS = Set.of(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원",
            "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private static final List<String> EVENT_ORDER = List.of("FULL", "HALF", "K10", "K5");
    private static final Set<String> CATEGORIES = Set.of("로드", "트레일", "걷기", "야간");
    private static final Set<String> REGISTRATION_STATUSES =
            Set.of("OPEN", "CLOSED", "BEFORE", "UNKNOWN");
    private static final Set<String> SOURCE_TYPES = Set.of("MARATHON_GO", "MARATHON_ONLINE");
    private static final Set<String> SKIP_REASONS = Set.of("MISSING_REQUIRED");
    private static final Set<String> PRIVATE_RAW_PAYLOAD_FIELDS =
            Set.of("contact_email", "contact_phone", "description");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TIME = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final BigDecimal MIN_LAT = new BigDecimal("33");
    private static final BigDecimal MAX_LAT = new BigDecimal("39");
    private static final BigDecimal MIN_LNG = new BigDecimal("124");
    private static final BigDecimal MAX_LNG = new BigDecimal("132");

    public void validate(ContestSnapshot snapshot) {
        List<String> errors = new ArrayList<>();
        if (snapshot == null) {
            throw new ContestSnapshotValidationException(List.of("최상위 객체가 없습니다"));
        }
        if (snapshot.schemaVersion() == null
                || snapshot.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            errors.add("schemaVersion은 1이어야 합니다");
        }
        validateMeta(snapshot.meta(), errors);
        validateContests(snapshot.contests(), snapshot.meta(), errors);
        if (!errors.isEmpty()) {
            throw new ContestSnapshotValidationException(errors);
        }
    }

    private void validateMeta(ContestSnapshot.Meta meta, List<String> errors) {
        if (meta == null) {
            errors.add("meta가 없습니다");
            return;
        }
        requireText(meta.source(), "meta.source", 255, errors);
        if (meta.sourceSha256() == null || !SHA_256.matcher(meta.sourceSha256()).matches()) {
            errors.add("meta.sourceSha256는 lowercase SHA-256 64자여야 합니다");
        }
        requireNonNegative(meta.sourceRowCount(), "meta.sourceRowCount", errors);
        requireNonNegative(meta.canonicalCount(), "meta.canonicalCount", errors);
        requireNonNegative(meta.sourceRecordCount(), "meta.sourceRecordCount", errors);
        requireNonNegative(meta.eventRecordCount(), "meta.eventRecordCount", errors);
        if (meta.skipped() == null) {
            errors.add("meta.skipped가 없습니다");
        } else {
            for (int i = 0; i < meta.skipped().size(); i++) {
                ContestSnapshot.SkippedSource skipped = meta.skipped().get(i);
                String path = "meta.skipped[" + i + "]";
                if (skipped == null) {
                    errors.add(path + "가 null입니다");
                    continue;
                }
                requireText(skipped.externalId(), path + ".externalId", 255, errors);
                if (!SKIP_REASONS.contains(skipped.reason())) {
                    errors.add(path + ".reason 값이 지원되지 않습니다");
                }
            }
        }
        parseUtcInstant(meta.checkedAtMax(), "meta.checkedAtMax", errors);
    }

    private void validateContests(
            List<ContestSnapshot.ContestItem> contests,
            ContestSnapshot.Meta meta,
            List<String> errors) {
        if (contests == null) {
            errors.add("contests가 없습니다");
            return;
        }

        Set<String> canonicalKeys = new HashSet<>();
        Set<String> sourceKeys = new HashSet<>();
        int sourceCount = 0;
        int eventCount = 0;
        Instant sourceCheckedAtMax = null;
        String previousContestSortKey = null;

        for (int i = 0; i < contests.size(); i++) {
            ContestSnapshot.ContestItem contest = contests.get(i);
            String path = "contests[" + i + "]";
            if (contest == null) {
                errors.add(path + "가 null입니다");
                continue;
            }
            requireText(contest.canonicalKey(), path + ".canonicalKey", 255, errors);
            if (contest.canonicalKey() != null && !canonicalKeys.add(contest.canonicalKey())) {
                errors.add(path + ".canonicalKey가 중복입니다");
            }
            requireText(contest.name(), path + ".name", 255, errors);
            if (!REGIONS.contains(contest.region())) {
                errors.add(path + ".region 값이 17개 시도 단축명에 없습니다");
            }
            requireText(contest.place(), path + ".place", 255, errors);
            optionalText(contest.roadAddress(), path + ".roadAddress", 255, errors);
            LocalDate contestDate = parseDate(contest.contestDate(), path + ".contestDate", errors);
            parseOptionalTime(contest.startTime(), path + ".startTime", errors);
            eventCount += validateEvents(contest.events(), path + ".events", errors);
            if (!CATEGORIES.contains(contest.category())) {
                errors.add(path + ".category 값이 지원되지 않습니다");
            }
            parseOptionalDate(contest.applyStart(), path + ".applyStart", errors);
            parseOptionalDate(contest.applyEnd(), path + ".applyEnd", errors);
            if (!REGISTRATION_STATUSES.contains(contest.regStatusFallback())) {
                errors.add(path + ".regStatusFallback 값이 지원되지 않습니다");
            }
            optionalText(contest.organizer(), path + ".organizer", 255, errors);
            optionalText(contest.officialUrl(), path + ".officialUrl", 2048, errors);
            optionalText(contest.detailUrl(), path + ".detailUrl", 2048, errors);
            optionalText(contest.imageUrl(), path + ".imageUrl", 2048, errors);
            validateCoordinate(contest.lat(), MIN_LAT, MAX_LAT, path + ".lat", errors);
            validateCoordinate(contest.lng(), MIN_LNG, MAX_LNG, path + ".lng", errors);
            Instant contestCheckedAt = parseUtcInstant(contest.checkedAt(), path + ".checkedAt", errors);
            Instant canonicalSourceMax = validateSources(contest.sources(), path, sourceKeys, errors);
            if (contest.sources() != null) {
                sourceCount += contest.sources().size();
            }
            if (contestCheckedAt != null
                    && canonicalSourceMax != null
                    && !contestCheckedAt.equals(canonicalSourceMax)) {
                errors.add(path + ".checkedAt이 sources[].fetchedAt 최댓값과 다릅니다");
            }
            if (canonicalSourceMax != null
                    && (sourceCheckedAtMax == null || canonicalSourceMax.isAfter(sourceCheckedAtMax))) {
                sourceCheckedAtMax = canonicalSourceMax;
            }

            if (contestDate != null && contest.canonicalKey() != null) {
                String sortKey = contestDate + "\u0000" + contest.canonicalKey();
                if (previousContestSortKey != null && previousContestSortKey.compareTo(sortKey) > 0) {
                    errors.add(path + "가 (contestDate, canonicalKey) 순서에 맞지 않습니다");
                }
                previousContestSortKey = sortKey;
            }
        }

        validateAggregates(meta, contests.size(), sourceCount, eventCount, sourceCheckedAtMax, errors);
    }

    private int validateEvents(List<String> events, String path, List<String> errors) {
        if (events == null) {
            errors.add(path + "가 없습니다");
            return 0;
        }
        Set<String> unique = new HashSet<>();
        int previousOrder = -1;
        for (int i = 0; i < events.size(); i++) {
            String event = events.get(i);
            int currentOrder = EVENT_ORDER.indexOf(event);
            if (currentOrder < 0) {
                errors.add(path + "[" + i + "] 값이 지원되지 않습니다");
            } else if (currentOrder <= previousOrder) {
                errors.add(path + "가 FULL, HALF, K10, K5 고정 순서가 아닙니다");
            }
            previousOrder = currentOrder;
            if (!unique.add(event)) {
                errors.add(path + "에 중복 종목이 있습니다");
            }
        }
        return events.size();
    }

    private Instant validateSources(
            List<ContestSnapshot.Source> sources,
            String contestPath,
            Set<String> sourceKeys,
            List<String> errors) {
        if (sources == null || sources.isEmpty()) {
            errors.add(contestPath + ".sources는 한 개 이상이어야 합니다");
            return null;
        }
        Instant max = null;
        String previousSortKey = null;
        for (int i = 0; i < sources.size(); i++) {
            ContestSnapshot.Source source = sources.get(i);
            String path = contestPath + ".sources[" + i + "]";
            if (source == null) {
                errors.add(path + "가 null입니다");
                continue;
            }
            if (!SOURCE_TYPES.contains(source.sourceType())) {
                errors.add(path + ".sourceType 값이 지원되지 않습니다");
            }
            requireText(source.externalId(), path + ".externalId", 255, errors);
            requireText(source.sourceUrl(), path + ".sourceUrl", 2048, errors);
            String sourceKey = source.sourceType() + "\u0000" + source.externalId();
            if (!sourceKeys.add(sourceKey)) {
                errors.add(path + "의 (sourceType, externalId)가 중복입니다");
            }
            Instant fetchedAt = parseUtcInstant(source.fetchedAt(), path + ".fetchedAt", errors);
            if (fetchedAt != null && (max == null || fetchedAt.isAfter(max))) {
                max = fetchedAt;
            }
            parseDate(source.lastCheckedDate(), path + ".lastCheckedDate", errors);
            if (source.rawPayload() == null || !source.rawPayload().isObject()) {
                errors.add(path + ".rawPayload는 객체여야 합니다");
            } else {
                PRIVATE_RAW_PAYLOAD_FIELDS.stream()
                        .filter(source.rawPayload()::has)
                        .forEach(field -> errors.add(path + ".rawPayload에 비보존 필드 " + field + "가 있습니다"));
                if (source.rawPayload().has("reg_status")
                        && (!source.rawPayload().get("reg_status").isTextual()
                                || source.rawPayload().get("reg_status").textValue().length() > 32)) {
                    errors.add(path + ".rawPayload.reg_status를 VARCHAR(32)에 저장할 수 없습니다");
                }
            }
            if (previousSortKey != null && previousSortKey.compareTo(sourceKey) > 0) {
                errors.add(contestPath + ".sources가 (sourceType, externalId) 순서에 맞지 않습니다");
            }
            previousSortKey = sourceKey;
        }
        return max;
    }

    private void validateAggregates(
            ContestSnapshot.Meta meta,
            int canonicalCount,
            int sourceCount,
            int eventCount,
            Instant sourceCheckedAtMax,
            List<String> errors) {
        if (meta == null) {
            return;
        }
        if (meta.canonicalCount() != null && meta.canonicalCount() != canonicalCount) {
            errors.add("meta.canonicalCount가 contests[] 길이와 다릅니다");
        }
        if (meta.sourceRecordCount() != null && meta.sourceRecordCount() != sourceCount) {
            errors.add("meta.sourceRecordCount가 sources[] 합계와 다릅니다");
        }
        if (meta.eventRecordCount() != null && meta.eventRecordCount() != eventCount) {
            errors.add("meta.eventRecordCount가 events[] 합계와 다릅니다");
        }
        if (meta.sourceRowCount() != null && meta.skipped() != null && meta.sourceRecordCount() != null) {
            int expectedSourceCount = meta.sourceRowCount() - meta.skipped().size();
            if (meta.sourceRecordCount() != expectedSourceCount) {
                errors.add("meta.sourceRecordCount가 sourceRowCount - skipped.length와 다릅니다");
            }
        }
        Instant metaCheckedAtMax = parseUtcInstant(meta.checkedAtMax(), "meta.checkedAtMax", new ArrayList<>());
        if (metaCheckedAtMax != null
                && sourceCheckedAtMax != null
                && !metaCheckedAtMax.equals(sourceCheckedAtMax)) {
            errors.add("meta.checkedAtMax가 전체 sources[].fetchedAt 최댓값과 다릅니다");
        }
        if (sourceCheckedAtMax == null) {
            errors.add("snapshot에 유효한 source fetchedAt이 없습니다");
        }
    }

    private void requireNonNegative(Integer value, String path, List<String> errors) {
        if (value == null || value < 0) {
            errors.add(path + "는 0 이상의 정수여야 합니다");
        }
    }

    private void requireText(String value, String path, int maxLength, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(path + "는 비어 있을 수 없습니다");
        } else if (value.length() > maxLength) {
            errors.add(path + "는 " + maxLength + "자를 넘을 수 없습니다");
        }
    }

    private void optionalText(String value, String path, int maxLength, List<String> errors) {
        if (value != null && value.length() > maxLength) {
            errors.add(path + "는 " + maxLength + "자를 넘을 수 없습니다");
        }
    }

    private LocalDate parseOptionalDate(String value, String path, List<String> errors) {
        return value == null ? null : parseDate(value, path, errors);
    }

    private LocalDate parseDate(String value, String path, List<String> errors) {
        if (value == null) {
            errors.add(path + "가 없습니다");
            return null;
        }
        try {
            return LocalDate.parse(value, STRICT_DATE);
        } catch (DateTimeParseException exception) {
            errors.add(path + "는 YYYY-MM-DD 형식이어야 합니다");
            return null;
        }
    }

    private LocalTime parseOptionalTime(String value, String path, List<String> errors) {
        if (value == null) {
            return null;
        }
        if (!TIME.matcher(value).matches()) {
            errors.add(path + "는 HH:MM 형식이어야 합니다");
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException exception) {
            errors.add(path + "는 유효한 시각이어야 합니다");
            return null;
        }
    }

    private Instant parseUtcInstant(String value, String path, List<String> errors) {
        if (value == null || !value.endsWith("Z")) {
            errors.add(path + "는 UTC Z timestamp여야 합니다");
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            errors.add(path + "는 유효한 UTC Z timestamp여야 합니다");
            return null;
        }
    }

    private void validateCoordinate(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            String path,
            List<String> errors) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            errors.add(path + "가 대한민국 개략 범위를 벗어났습니다");
        }
    }
}

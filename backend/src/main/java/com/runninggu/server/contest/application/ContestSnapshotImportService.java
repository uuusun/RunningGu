package com.runninggu.server.contest.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.runninggu.server.contest.application.snapshot.ContestSnapshot;
import com.runninggu.server.contest.application.snapshot.ContestSnapshotFile;
import com.runninggu.server.contest.application.snapshot.ContestSnapshotValidator;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestCategory;
import com.runninggu.server.contest.domain.ContestEvent;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatus;
import com.runninggu.server.contest.domain.ContestSnapshotImport;
import com.runninggu.server.contest.domain.ContestSource;
import com.runninggu.server.contest.domain.ContestSourceType;
import com.runninggu.server.contest.infrastructure.ContestEventRepository;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.contest.infrastructure.ContestSnapshotImportLock;
import com.runninggu.server.contest.infrastructure.ContestSnapshotImportRepository;
import com.runninggu.server.contest.infrastructure.ContestSourceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContestSnapshotImportService {

    private static final Logger log = LoggerFactory.getLogger(ContestSnapshotImportService.class);

    private final ContestSnapshotValidator validator;
    private final ContestSnapshotImportLock importLock;
    private final ContestSnapshotImportRepository importRepository;
    private final ContestRepository contestRepository;
    private final ContestSourceRepository sourceRepository;
    private final ContestEventRepository eventRepository;
    private final Clock clock;

    public ContestSnapshotImportService(
            ContestSnapshotValidator validator,
            ContestSnapshotImportLock importLock,
            ContestSnapshotImportRepository importRepository,
            ContestRepository contestRepository,
            ContestSourceRepository sourceRepository,
            ContestEventRepository eventRepository,
            Clock clock) {
        this.validator = validator;
        this.importLock = importLock;
        this.importRepository = importRepository;
        this.contestRepository = contestRepository;
        this.sourceRepository = sourceRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /** 검증·canonical 승계·하위 갱신·적용 이력을 한 트랜잭션으로 처리한다. (SPEC §8.2) */
    @Transactional
    public ContestSnapshotImportResult importSnapshot(ContestSnapshotFile snapshotFile) {
        ContestSnapshot snapshot = snapshotFile.snapshot();
        String snapshotSha256 = snapshotFile.snapshotSha256();
        validator.validate(snapshot);
        importLock.acquire();

        Instant checkedAtMax = Instant.parse(snapshot.meta().checkedAtMax());
        if (importRepository
                .findBySnapshotSha256AndCheckedAtMax(snapshotSha256, checkedAtMax)
                .isPresent()) {
            return ContestSnapshotImportResult.noOp();
        }
        rejectOutOfOrder(snapshotSha256, checkedAtMax);

        Instant mutationAt = clock.instant();
        List<Contest> contests = new ArrayList<>(contestRepository.findAll());
        List<ContestSource> sources = new ArrayList<>(sourceRepository.findAll());
        Map<Long, Contest> contestById = new HashMap<>();
        Map<String, Contest> contestByCanonicalKey = new HashMap<>();
        for (Contest contest : contests) {
            contestById.put(contest.getId(), contest);
            contestByCanonicalKey.put(contest.getCanonicalKey(), contest);
        }
        Map<SourceKey, ContestSource> sourceByKey = new HashMap<>();
        for (ContestSource source : sources) {
            sourceByKey.put(new SourceKey(source.getSourceType(), source.getExternalId()), source);
        }
        List<PlannedContest> plans = planSuccession(
                snapshot.contests(), contestByCanonicalKey, sourceByKey, contestById);

        int insertedContests = 0;
        int updatedContests = 0;
        int importedSources = 0;
        int importedEvents = 0;
        Set<SourceKey> seenSourceKeys = new HashSet<>();

        for (PlannedContest plan : plans) {
            ContestSnapshot.ContestItem incoming = plan.incoming();
            Contest target = plan.target();
            boolean inserted = false;
            if (target == null) {
                target = Contest.create(incoming.canonicalKey(), mutationAt);
                contests.add(target);
                inserted = true;
            }

            applyCanonical(target, incoming, mutationAt);
            target = contestRepository.save(target);
            contestById.put(target.getId(), target);

            replaceEvents(target, incoming.events());
            importedEvents += incoming.events().size();

            for (ContestSnapshot.Source incomingSource : incoming.sources()) {
                SourceKey sourceKey = SourceKey.from(incomingSource);
                ContestSource source = sourceByKey.get(sourceKey);
                if (source == null) {
                    source = ContestSource.create(sourceKey.sourceType(), sourceKey.externalId());
                    sources.add(source);
                    sourceByKey.put(sourceKey, source);
                }
                source.applySnapshot(
                        target,
                        incomingSource.sourceUrl(),
                        rawStatus(incomingSource.rawPayload()),
                        Instant.parse(incomingSource.fetchedAt()),
                        incomingSource.rawPayload());
                sourceRepository.save(source);
                seenSourceKeys.add(sourceKey);
                importedSources++;
            }

            if (inserted) {
                insertedContests++;
            } else {
                updatedContests++;
            }
        }

        for (Map.Entry<SourceKey, ContestSource> entry : sourceByKey.entrySet()) {
            if (!seenSourceKeys.contains(entry.getKey())) {
                entry.getValue().markMissing();
            }
        }
        sourceRepository.flush();
        updateCanonicalActive(contests, sources, mutationAt);

        Instant appliedAt = clock.instant();
        importRepository.save(new ContestSnapshotImport(
                snapshot.schemaVersion(),
                snapshotSha256,
                snapshot.meta().sourceSha256(),
                checkedAtMax,
                appliedAt));

        return new ContestSnapshotImportResult(
                ContestSnapshotImportResult.Status.APPLIED,
                insertedContests,
                updatedContests,
                importedSources,
                importedEvents);
    }

    private void rejectOutOfOrder(String snapshotSha256, Instant checkedAtMax) {
        importRepository.findTopByOrderByCheckedAtMaxDesc().ifPresent(latest -> {
            if (checkedAtMax.isBefore(latest.getCheckedAtMax())) {
                throw new ContestSnapshotOrderException("마지막 성공 이력보다 과거 snapshot입니다");
            }
            if (checkedAtMax.equals(latest.getCheckedAtMax())
                    && !snapshotSha256.equals(latest.getSnapshotSha256())) {
                throw new ContestSnapshotOrderException("같은 checkedAtMax의 snapshot hash가 다릅니다");
            }
        });
    }

    /** DB 변경 전에 전체 승계를 계획하고 애매한 PK 승계를 거부한다. (SPEC §8.2, 결정-48) */
    private List<PlannedContest> planSuccession(
            List<ContestSnapshot.ContestItem> incomingContests,
            Map<String, Contest> contestByCanonicalKey,
            Map<SourceKey, ContestSource> sourceByKey,
            Map<Long, Contest> contestById) {
        List<PlannedContest> plans = new ArrayList<>();
        Set<Long> claimedContestIds = new HashSet<>();
        for (ContestSnapshot.ContestItem incoming : incomingContests) {
            Contest target = contestByCanonicalKey.get(incoming.canonicalKey());
            if (target == null) {
                target = selectSourceSuccessionTarget(incoming, sourceByKey, contestById);
            }
            if (target != null && !claimedContestIds.add(target.getId())) {
                throw new AmbiguousContestSuccessionException(
                        incoming.canonicalKey(), "하나의 기존 canonical을 둘 이상이 본체로 선택함");
            }
            plans.add(new PlannedContest(incoming, target));
        }
        return plans;
    }

    private Contest selectSourceSuccessionTarget(
            ContestSnapshot.ContestItem incoming,
            Map<SourceKey, ContestSource> sourceByKey,
            Map<Long, Contest> contestById) {
        Map<Long, Integer> overlapCounts = new LinkedHashMap<>();
        for (ContestSnapshot.Source source : incoming.sources()) {
            ContestSource existing = sourceByKey.get(SourceKey.from(source));
            if (existing != null) {
                overlapCounts.merge(existing.getContest().getId(), 1, Integer::sum);
            }
        }
        if (overlapCounts.isEmpty()) {
            return null;
        }

        int maximum = overlapCounts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        List<Long> winners = overlapCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == maximum)
                .map(Map.Entry::getKey)
                .toList();
        if (winners.size() != 1) {
            throw new AmbiguousContestSuccessionException(
                    incoming.canonicalKey(), "최대 source 겹침 수가 동률임");
        }
        return contestById.get(winners.getFirst());
    }

    private void applyCanonical(
            Contest contest, ContestSnapshot.ContestItem incoming, Instant appliedAt) {
        contest.update(
                incoming.canonicalKey(),
                incoming.name(),
                incoming.region(),
                incoming.place(),
                incoming.roadAddress(),
                incoming.lat(),
                incoming.lng(),
                LocalDate.parse(incoming.contestDate()),
                incoming.startTime() == null ? null : LocalTime.parse(incoming.startTime()),
                ContestRegistrationStatus.valueOf(incoming.regStatusFallback()),
                incoming.applyStart() == null ? null : LocalDate.parse(incoming.applyStart()),
                incoming.applyEnd() == null ? null : LocalDate.parse(incoming.applyEnd()),
                incoming.organizer(),
                incoming.officialUrl(),
                incoming.detailUrl(),
                incoming.imageUrl(),
                categoryOf(incoming.category()),
                Instant.parse(incoming.checkedAt()),
                appliedAt);
    }

    private void replaceEvents(Contest contest, List<String> incomingEvents) {
        eventRepository.deleteAllByContestId(contest.getId());
        eventRepository.flush();
        eventRepository.saveAll(incomingEvents.stream()
                .map(value -> new ContestEvent(contest, ContestEventType.valueOf(value)))
                .toList());
    }

    private void updateCanonicalActive(
            List<Contest> contests, List<ContestSource> sources, Instant appliedAt) {
        Set<Long> activeContestIds = new HashSet<>();
        Map<Long, Integer> sourceCounts = new HashMap<>();
        for (ContestSource source : sources) {
            Long contestId = source.getContest().getId();
            sourceCounts.merge(contestId, 1, Integer::sum);
            if (source.isActive()) {
                activeContestIds.add(contestId);
            }
        }
        for (Contest contest : contests) {
            contest.updateActive(activeContestIds.contains(contest.getId()), appliedAt);
            if (!sourceCounts.containsKey(contest.getId())) {
                log.warn("source가 0개인 canonical 삭제 후보: contestId={}", contest.getId());
            }
        }
        contestRepository.flush();
    }

    private ContestCategory categoryOf(String category) {
        return switch (category) {
            case "로드" -> ContestCategory.ROAD;
            case "트레일" -> ContestCategory.TRAIL;
            case "걷기" -> ContestCategory.WALK;
            case "야간" -> ContestCategory.NIGHT;
            default -> throw new IllegalArgumentException("검증되지 않은 category: " + category);
        };
    }

    private String rawStatus(JsonNode rawPayload) {
        JsonNode value = rawPayload.get("reg_status");
        if (value == null || value.isNull() || value.textValue().isBlank()) {
            return null;
        }
        return value.textValue();
    }

    private record SourceKey(ContestSourceType sourceType, String externalId) {
        private static SourceKey from(ContestSnapshot.Source source) {
            return new SourceKey(ContestSourceType.valueOf(source.sourceType()), source.externalId());
        }
    }

    private record PlannedContest(ContestSnapshot.ContestItem incoming, Contest target) {}
}

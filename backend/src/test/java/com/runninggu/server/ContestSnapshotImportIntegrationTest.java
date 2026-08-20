package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.contest.application.AmbiguousContestSuccessionException;
import com.runninggu.server.contest.application.ContestSnapshotImportResult;
import com.runninggu.server.contest.application.ContestSnapshotImportService;
import com.runninggu.server.contest.application.ContestSnapshotOrderException;
import com.runninggu.server.contest.application.snapshot.ContestSnapshot;
import com.runninggu.server.contest.application.snapshot.ContestSnapshotFile;
import com.runninggu.server.contest.application.snapshot.ContestSnapshotValidationException;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.infrastructure.ContestEventRepository;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.contest.infrastructure.ContestSnapshotFileReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ContestSnapshotImportIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private ContestSnapshotFileReader fileReader;

    @Autowired
    private ContestSnapshotImportService importService;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private ContestEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void truncateTables() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE contest_snapshot_import, contest_event, contest_source, contest RESTART IDENTITY CASCADE");
    }

    @Test
    void nullable_컬럼과_하위_데이터와_적용_이력을_원자적으로_적재한다() throws Exception {
        ContestSnapshot snapshot = fixture();
        ContestSnapshotFile snapshotFile = snapshotFile(snapshot);

        ContestSnapshotImportResult result = importService.importSnapshot(snapshotFile);

        assertThat(result.status()).isEqualTo(ContestSnapshotImportResult.Status.APPLIED);
        assertThat(result.insertedContests()).isEqualTo(1);
        Contest contest = contestRepository.findAll().getFirst();
        assertThat(contest.getRoadAddress()).isNull();
        assertThat(contest.getStartTime()).isNull();
        assertThat(contest.getDetailUrl()).isNull();
        assertThat(contest.isActive()).isTrue();
        assertThat(eventRepository.findAllByContestId(contest.getId()))
                .extracting(event -> event.getEventType().name())
                .containsExactlyInAnyOrder("K10", "K5");
        assertThat(count("contest_source")).isEqualTo(2);
        assertThat(count("contest_snapshot_import")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT snapshot_sha256 FROM contest_snapshot_import", String.class))
                .isEqualTo(snapshotFile.snapshotSha256());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT source_sha256 FROM contest_snapshot_import", String.class))
                .isEqualTo(snapshot.meta().sourceSha256());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT raw_payload ->> 'race_id' FROM contest_source WHERE external_id = ?",
                        String.class,
                        "go-seoul-2026"))
                .isEqualTo("go-seoul-2026");
    }

    @Test
    void 저장소의_전체_snapshot을_적재하고_재실행하면_no_op이다() {
        ContestSnapshotFile snapshotFile =
                fileReader.read(Path.of("..", "data", "contest_snapshot.json"));
        ContestSnapshot snapshot = snapshotFile.snapshot();
        ContestSnapshot.ContestItem timedContest = snapshot.contests().stream()
                .filter(contest -> contest.startTime() != null)
                .findFirst()
                .orElseThrow();

        ContestSnapshotImportResult applied = importService.importSnapshot(snapshotFile);
        ContestSnapshotImportResult repeated = importService.importSnapshot(snapshotFile);

        assertThat(applied.insertedContests()).isEqualTo(snapshot.meta().canonicalCount());
        assertThat(count("contest")).isEqualTo(snapshot.meta().canonicalCount().longValue());
        assertThat(count("contest_source")).isEqualTo(snapshot.meta().sourceRecordCount().longValue());
        assertThat(count("contest_event")).isEqualTo(snapshot.meta().eventRecordCount().longValue());
        assertThat(repeated.status()).isEqualTo(ContestSnapshotImportResult.Status.NO_OP);
        assertThat(count("contest_snapshot_import")).isEqualTo(1);
        assertThat(contestRepository.findByCanonicalKey(timedContest.canonicalKey()).orElseThrow()
                        .getStartTime())
                .isEqualTo(LocalTime.parse(timedContest.startTime()));
    }

    @Test
    void import_history_lock이_동시_적재의_최신이력_판정을_직렬화한다() throws Exception {
        ContestSnapshotFile snapshotFile = snapshotFile(fixture());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<ContestSnapshotImportResult> future;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE contest_snapshot_import IN EXCLUSIVE MODE");
            future = CompletableFuture.supplyAsync(
                    () -> importService.importSnapshot(snapshotFile), executor);

            assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            connection.commit();
        }

        assertThat(future.get(5, TimeUnit.SECONDS).status())
                .isEqualTo(ContestSnapshotImportResult.Status.APPLIED);
        executor.shutdownNow();
    }

    @Test
    void 같은_snapshot은_no_op이고_누락_횟수를_다시_올리지_않는다() throws Exception {
        ContestSnapshot initial = fixture();
        importService.importSnapshot(snapshotFile(initial));
        ContestSnapshot firstMissing = version(
                initial,
                '2',
                "2026-06-14T02:00:00Z",
                List.of(initial.contests().getFirst().sources().getFirst()),
                initial.contests().getFirst().events(),
                initial.contests().getFirst().canonicalKey(),
                initial.contests().getFirst().name());

        ContestSnapshotFile firstMissingFile = snapshotFile(firstMissing);
        importService.importSnapshot(firstMissingFile);
        ContestSnapshotImportResult repeated = importService.importSnapshot(firstMissingFile);

        assertThat(repeated.status()).isEqualTo(ContestSnapshotImportResult.Status.NO_OP);
        assertThat(missingCount("online-seoul-2026")).isEqualTo(1);
        assertThat(count("contest_snapshot_import")).isEqualTo(2);
    }

    @Test
    void 두_번_연속_누락하면_source를_비활성화하고_재등장하면_복구한다() throws Exception {
        ContestSnapshot initial = fixture();
        importService.importSnapshot(snapshotFile(initial));
        ContestSnapshot.Source goSource = initial.contests().getFirst().sources().getFirst();
        ContestSnapshot.Source onlineSource = initial.contests().getFirst().sources().get(1);

        importService.importSnapshot(snapshotFile(version(
                initial,
                '2',
                "2026-06-14T02:00:00Z",
                List.of(goSource),
                initial.contests().getFirst().events(),
                initial.contests().getFirst().canonicalKey(),
                initial.contests().getFirst().name())));
        importService.importSnapshot(snapshotFile(version(
                initial,
                '3',
                "2026-06-14T03:00:00Z",
                List.of(goSource),
                initial.contests().getFirst().events(),
                initial.contests().getFirst().canonicalKey(),
                initial.contests().getFirst().name())));

        assertThat(missingCount("online-seoul-2026")).isEqualTo(2);
        assertThat(sourceActive("online-seoul-2026")).isFalse();
        assertThat(contestRepository.findAll().getFirst().isActive()).isTrue();

        importService.importSnapshot(snapshotFile(version(
                initial,
                '4',
                "2026-06-14T04:00:00Z",
                List.of(goSource, onlineSource),
                initial.contests().getFirst().events(),
                initial.contests().getFirst().canonicalKey(),
                initial.contests().getFirst().name())));

        assertThat(missingCount("online-seoul-2026")).isZero();
        assertThat(sourceActive("online-seoul-2026")).isTrue();
    }

    @Test
    void 과거_snapshot과_동일시각의_다른_파일_hash를_거부한다() throws Exception {
        ContestSnapshot initial = fixture();
        importService.importSnapshot(snapshotFile(initial, 'a'));
        ContestSnapshot.ContestItem contest = initial.contests().getFirst();

        ContestSnapshot older = version(
                initial,
                '2',
                "2026-06-14T01:00:00Z",
                contest.sources(),
                contest.events(),
                contest.canonicalKey(),
                contest.name());
        ContestSnapshot sameTimeDifferentHash = version(
                initial,
                '1',
                initial.meta().checkedAtMax(),
                contest.sources(),
                contest.events(),
                contest.canonicalKey(),
                contest.name());

        assertThatThrownBy(() -> importService.importSnapshot(snapshotFile(older, 'b')))
                .isInstanceOf(ContestSnapshotOrderException.class)
                .hasMessageContaining("과거");
        assertThat(sameTimeDifferentHash.meta().sourceSha256())
                .isEqualTo(initial.meta().sourceSha256());
        assertThatThrownBy(() ->
                        importService.importSnapshot(snapshotFile(sameTimeDifferentHash, 'c')))
                .isInstanceOf(ContestSnapshotOrderException.class)
                .hasMessageContaining("hash");
        assertThat(count("contest_snapshot_import")).isEqualTo(1);
    }

    @Test
    void 검증_실패는_기존_DB와_적용_이력을_바꾸지_않는다() throws Exception {
        ContestSnapshot initial = fixture();
        importService.importSnapshot(snapshotFile(initial));
        ContestSnapshot.Meta invalidMeta = new ContestSnapshot.Meta(
                initial.meta().source(),
                repeat('2'),
                initial.meta().sourceRowCount(),
                99,
                initial.meta().sourceRecordCount(),
                initial.meta().eventRecordCount(),
                initial.meta().skipped(),
                "2026-06-14T02:00:00Z");

        assertThatThrownBy(() -> importService.importSnapshot(snapshotFile(
                        new ContestSnapshot(initial.schemaVersion(), invalidMeta, initial.contests()))))
                .isInstanceOf(ContestSnapshotValidationException.class);
        assertThat(contestRepository.findAll().getFirst().getName()).isEqualTo("서울 달리기");
        assertThat(count("contest_snapshot_import")).isEqualTo(1);
    }

    @Test
    void source_승계로_canonical_PK를_유지하고_event를_완전_교체한다() throws Exception {
        ContestSnapshot initial = fixture();
        importService.importSnapshot(snapshotFile(initial));
        Long originalId = contestRepository.findAll().getFirst().getId();
        ContestSnapshot.ContestItem contest = initial.contests().getFirst();
        ContestSnapshot renamed = version(
                initial,
                '2',
                "2026-06-14T02:00:00Z",
                contest.sources(),
                List.of("FULL"),
                "2026-10-18|서울달리기대회",
                "서울 달리기 대회");

        importService.importSnapshot(snapshotFile(renamed));

        Contest updated = contestRepository.findAll().getFirst();
        assertThat(updated.getId()).isEqualTo(originalId);
        assertThat(updated.getCanonicalKey()).isEqualTo("2026-10-18|서울달리기대회");
        assertThat(updated.getName()).isEqualTo("서울 달리기 대회");
        assertThat(eventRepository.findAllByContestId(originalId))
                .extracting(event -> event.getEventType().name())
                .containsExactly("FULL");
    }

    @Test
    void 재병합은_source_겹침이_많은_canonical의_PK를_본체로_쓴다() throws Exception {
        ContestSnapshot base = fixture();
        ContestSnapshot.Source go = base.contests().getFirst().sources().getFirst();
        ContestSnapshot.Source online = base.contests().getFirst().sources().get(1);
        ContestSnapshot.Source third = new ContestSnapshot.Source(
                "MARATHON_ONLINE",
                "online-busan-2026",
                "https://example.com/online-busan-2026",
                "2026-06-14T01:10:00Z",
                "2026-06-14",
                online.rawPayload());
        ContestSnapshot.ContestItem template = base.contests().getFirst();
        ContestSnapshot.ContestItem first = contest(
                template, "2026-10-18|a대회", "A 대회", List.of(go, online), List.of("K10"), "2026-06-14T01:10:00Z");
        ContestSnapshot.ContestItem second = contest(
                template, "2026-10-18|b대회", "B 대회", List.of(third), List.of("K5"), "2026-06-14T01:10:00Z");
        ContestSnapshot initial = snapshot(base, '1', "2026-06-14T01:10:00Z", List.of(first, second));
        importService.importSnapshot(snapshotFile(initial));
        Long firstId = contestRepository.findByCanonicalKey(first.canonicalKey()).orElseThrow().getId();
        Long secondId = contestRepository.findByCanonicalKey(second.canonicalKey()).orElseThrow().getId();

        ContestSnapshot.ContestItem merged = contest(
                template,
                "2026-10-18|합친대회",
                "합친 대회",
                List.of(
                        withFetchedAt(go, "2026-06-14T02:00:00Z"),
                        withFetchedAt(third, "2026-06-14T02:00:00Z"),
                        withFetchedAt(online, "2026-06-14T02:00:00Z")),
                List.of("FULL"),
                "2026-06-14T02:00:00Z");
        importService.importSnapshot(snapshotFile(
                snapshot(base, '2', "2026-06-14T02:00:00Z", List.of(merged))));

        Contest body = contestRepository.findByCanonicalKey(merged.canonicalKey()).orElseThrow();
        assertThat(body.getId()).isEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contest_source WHERE contest_id = ?", Long.class, firstId))
                .isEqualTo(3);
        assertThat(contestRepository.findById(secondId).orElseThrow().isActive()).isFalse();
        assertThat(count("contest")).isEqualTo(2);
    }

    @Test
    void 재병합_source_겹침이_동률이면_snapshot_전체를_롤백한다() throws Exception {
        ContestSnapshot base = fixture();
        ContestSnapshot.ContestItem template = base.contests().getFirst();
        ContestSnapshot.Source go = template.sources().getFirst();
        ContestSnapshot.Source online = template.sources().get(1);
        ContestSnapshot.ContestItem first = contest(
                template,
                "2026-10-18|a대회",
                "A 대회",
                List.of(go),
                List.of("K10"),
                go.fetchedAt());
        ContestSnapshot.ContestItem second = contest(
                template, "2026-10-18|b대회", "B 대회", List.of(online), List.of("K5"), base.meta().checkedAtMax());
        importService.importSnapshot(snapshotFile(snapshot(
                base, '1', base.meta().checkedAtMax(), List.of(first, second))));
        ContestSnapshot.ContestItem merged = contest(
                template,
                "2026-10-18|c대회",
                "C 대회",
                List.of(
                        withFetchedAt(go, "2026-06-14T02:00:00Z"),
                        withFetchedAt(online, "2026-06-14T02:00:00Z")),
                List.of("FULL"),
                "2026-06-14T02:00:00Z");

        assertThatThrownBy(() -> importService.importSnapshot(snapshotFile(
                        snapshot(base, '2', "2026-06-14T02:00:00Z", List.of(merged)))))
                .isInstanceOf(AmbiguousContestSuccessionException.class)
                .hasMessageContaining("동률");
        assertThat(count("contest")).isEqualTo(2);
        assertThat(count("contest_snapshot_import")).isEqualTo(1);
    }

    @Test
    void 기존_canonical이_둘로_분리되면_snapshot_전체를_롤백한다() throws Exception {
        ContestSnapshot base = fixture();
        importService.importSnapshot(snapshotFile(base));
        ContestSnapshot.ContestItem template = base.contests().getFirst();
        ContestSnapshot.ContestItem first = contest(
                template,
                "2026-10-18|a대회",
                "A 대회",
                List.of(withFetchedAt(template.sources().getFirst(), "2026-06-14T02:00:00Z")),
                List.of("K10"),
                "2026-06-14T02:00:00Z");
        ContestSnapshot.ContestItem second = contest(
                template,
                "2026-10-18|b대회",
                "B 대회",
                List.of(withFetchedAt(template.sources().get(1), "2026-06-14T02:00:00Z")),
                List.of("K5"),
                "2026-06-14T02:00:00Z");

        assertThatThrownBy(() -> importService.importSnapshot(snapshotFile(
                        snapshot(base, '2', "2026-06-14T02:00:00Z", List.of(first, second)))))
                .isInstanceOf(AmbiguousContestSuccessionException.class)
                .hasMessageContaining("둘 이상");
        assertThat(contestRepository.findAll())
                .singleElement()
                .extracting(Contest::getCanonicalKey)
                .isEqualTo(template.canonicalKey());
        assertThat(count("contest_snapshot_import")).isEqualTo(1);
    }

    private ContestSnapshot fixture() throws URISyntaxException {
        Path path = Path.of(Objects.requireNonNull(
                                getClass().getResource("/contest/valid-contest-snapshot.json"))
                        .toURI());
        return fileReader.read(path).snapshot();
    }

    private ContestSnapshotFile snapshotFile(ContestSnapshot snapshot) {
        return new ContestSnapshotFile(snapshot, snapshot.meta().sourceSha256());
    }

    private ContestSnapshotFile snapshotFile(ContestSnapshot snapshot, char hashCharacter) {
        return new ContestSnapshotFile(snapshot, repeat(hashCharacter));
    }

    private ContestSnapshot version(
            ContestSnapshot base,
            char hashCharacter,
            String checkedAt,
            List<ContestSnapshot.Source> sources,
            List<String> events,
            String canonicalKey,
            String name) {
        List<ContestSnapshot.Source> timestampedSources = sources.stream()
                .map(source -> new ContestSnapshot.Source(
                        source.sourceType(),
                        source.externalId(),
                        source.sourceUrl(),
                        checkedAt,
                        source.lastCheckedDate(),
                        source.rawPayload()))
                .toList();
        ContestSnapshot.ContestItem item = contest(
                base.contests().getFirst(),
                canonicalKey,
                name,
                timestampedSources,
                events,
                checkedAt);
        return snapshot(base, hashCharacter, checkedAt, List.of(item));
    }

    private ContestSnapshot snapshot(
            ContestSnapshot base,
            char hashCharacter,
            String checkedAt,
            List<ContestSnapshot.ContestItem> contests) {
        int sourceCount = contests.stream().mapToInt(item -> item.sources().size()).sum();
        int eventCount = contests.stream().mapToInt(item -> item.events().size()).sum();
        ContestSnapshot.Meta meta = new ContestSnapshot.Meta(
                base.meta().source(),
                repeat(hashCharacter),
                sourceCount,
                contests.size(),
                sourceCount,
                eventCount,
                List.of(),
                checkedAt);
        return new ContestSnapshot(base.schemaVersion(), meta, contests);
    }

    private ContestSnapshot.ContestItem contest(
            ContestSnapshot.ContestItem base,
            String canonicalKey,
            String name,
            List<ContestSnapshot.Source> sources,
            List<String> events,
            String checkedAt) {
        return new ContestSnapshot.ContestItem(
                canonicalKey,
                name,
                base.region(),
                base.place(),
                base.roadAddress(),
                base.contestDate(),
                base.startTime(),
                events,
                base.category(),
                base.applyStart(),
                base.applyEnd(),
                base.regStatusFallback(),
                base.organizer(),
                base.officialUrl(),
                base.detailUrl(),
                base.imageUrl(),
                base.lat(),
                base.lng(),
                checkedAt,
                new ArrayList<>(sources));
    }

    private ContestSnapshot.Source withFetchedAt(
            ContestSnapshot.Source source, String fetchedAt) {
        return new ContestSnapshot.Source(
                source.sourceType(),
                source.externalId(),
                source.sourceUrl(),
                fetchedAt,
                source.lastCheckedDate(),
                source.rawPayload());
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private int missingCount(String externalId) {
        return jdbcTemplate.queryForObject(
                "SELECT consecutive_missing_count FROM contest_source WHERE external_id = ?",
                Integer.class,
                externalId);
    }

    private boolean sourceActive(String externalId) {
        return jdbcTemplate.queryForObject(
                "SELECT active FROM contest_source WHERE external_id = ?", Boolean.class, externalId);
    }

    private String repeat(char value) {
        return String.valueOf(value).repeat(64);
    }
}

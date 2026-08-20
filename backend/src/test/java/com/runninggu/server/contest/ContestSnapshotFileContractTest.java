package com.runninggu.server.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.contest.application.snapshot.ContestSnapshot;
import com.runninggu.server.contest.application.snapshot.ContestSnapshotValidationException;
import com.runninggu.server.contest.application.snapshot.ContestSnapshotValidator;
import com.runninggu.server.contest.infrastructure.ContestSnapshotFileReader;
import com.runninggu.server.contest.infrastructure.ContestSnapshotReadException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContestSnapshotFileContractTest {

    private final ContestSnapshotFileReader reader;
    private final ContestSnapshotValidator validator = new ContestSnapshotValidator();

    ContestSnapshotFileContractTest() {
        ObjectMapper objectMapper = new ObjectMapper();
        reader = new ContestSnapshotFileReader(objectMapper);
    }

    @Test
    void fixture를_읽고_알_수_없는_필드는_무시한다() throws URISyntaxException {
        ContestSnapshot snapshot = reader.read(fixturePath());

        validator.validate(snapshot);

        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.contests()).hasSize(1);
        assertThat(snapshot.contests().getFirst().roadAddress()).isNull();
        assertThat(snapshot.contests().getFirst().startTime()).isNull();
        assertThat(snapshot.contests().getFirst().detailUrl()).isNull();
    }

    @Test
    void 실제_저장소_snapshot이_파일_계약을_만족한다() {
        ContestSnapshot snapshot = reader.read(Path.of("..", "data", "contest_snapshot.json"));

        validator.validate(snapshot);

        assertThat(snapshot.contests()).hasSize(snapshot.meta().canonicalCount());
    }

    @Test
    void 집계가_다르면_전체_검증을_거부한다() throws URISyntaxException {
        ContestSnapshot valid = reader.read(fixturePath());
        ContestSnapshot.Meta invalidMeta = new ContestSnapshot.Meta(
                valid.meta().source(),
                valid.meta().sourceSha256(),
                valid.meta().sourceRowCount(),
                2,
                valid.meta().sourceRecordCount(),
                valid.meta().eventRecordCount(),
                valid.meta().skipped(),
                valid.meta().checkedAtMax());

        assertThatThrownBy(() -> validator.validate(
                        new ContestSnapshot(valid.schemaVersion(), invalidMeta, valid.contests())))
                .isInstanceOf(ContestSnapshotValidationException.class)
                .hasMessageContaining("canonicalCount");
    }

    @Test
    void UTF8_BOM과_잘못된_UTF8을_거부한다(@TempDir Path tempDir) throws Exception {
        Path bom = tempDir.resolve("bom.json");
        Files.write(bom, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'});
        Path malformed = tempDir.resolve("malformed.json");
        Files.write(malformed, new byte[] {'{', '"', (byte) 0xC3, '"', ':', '1', '}'});

        assertThatThrownBy(() -> reader.read(bom))
                .isInstanceOf(ContestSnapshotReadException.class)
                .hasMessageContaining("BOM");
        assertThatThrownBy(() -> reader.read(malformed))
                .isInstanceOf(ContestSnapshotReadException.class)
                .hasMessageContaining("읽을 수 없습니다");
    }

    private Path fixturePath() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                                getClass().getResource("/contest/valid-contest-snapshot.json"))
                        .toURI());
    }
}

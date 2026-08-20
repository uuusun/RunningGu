package com.runninggu.server.contest.infrastructure;

import com.runninggu.server.contest.domain.ContestSnapshotImport;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContestSnapshotImportRepository
        extends JpaRepository<ContestSnapshotImport, Long> {

    Optional<ContestSnapshotImport> findBySourceSha256AndCheckedAtMax(
            String sourceSha256, Instant checkedAtMax);

    Optional<ContestSnapshotImport> findTopByOrderByCheckedAtMaxDesc();
}

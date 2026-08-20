package com.runninggu.server.contest.infrastructure;

import com.runninggu.server.contest.domain.ContestSource;
import com.runninggu.server.contest.domain.ContestSourceType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContestSourceRepository extends JpaRepository<ContestSource, Long> {
    Optional<ContestSource> findBySourceTypeAndExternalId(
            ContestSourceType sourceType, String externalId);
}

package com.runninggu.server.contest.infrastructure;

import com.runninggu.server.contest.domain.Contest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContestRepository extends JpaRepository<Contest, Long> {
    Optional<Contest> findByCanonicalKey(String canonicalKey);
}

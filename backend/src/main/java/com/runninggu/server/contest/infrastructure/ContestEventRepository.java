package com.runninggu.server.contest.infrastructure;

import com.runninggu.server.contest.domain.ContestEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContestEventRepository extends JpaRepository<ContestEvent, Long> {
    List<ContestEvent> findAllByContestId(Long contestId);

    void deleteAllByContestId(Long contestId);
}

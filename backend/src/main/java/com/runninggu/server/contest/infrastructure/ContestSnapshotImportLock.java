package com.runninggu.server.contest.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContestSnapshotImportLock {

    private final JdbcTemplate jdbcTemplate;

    public ContestSnapshotImportLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 최신 이력 판정과 insert 사이를 PostgreSQL table lock으로 직렬화한다. (결정-47) */
    public void acquire() {
        jdbcTemplate.execute("LOCK TABLE contest_snapshot_import IN EXCLUSIVE MODE");
    }
}

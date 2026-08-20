package com.runninggu.server.contest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "contest_snapshot_import")
public class ContestSnapshotImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "source_sha256", nullable = false, length = 64)
    private String sourceSha256;

    @Column(name = "checked_at_max", nullable = false, unique = true)
    private Instant checkedAtMax;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected ContestSnapshotImport() {}

    public ContestSnapshotImport(
            int schemaVersion, String sourceSha256, Instant checkedAtMax, Instant appliedAt) {
        this.schemaVersion = schemaVersion;
        this.sourceSha256 = sourceSha256;
        this.checkedAtMax = checkedAtMax;
        this.appliedAt = appliedAt;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public Instant getCheckedAtMax() {
        return checkedAtMax;
    }
}

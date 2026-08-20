package com.runninggu.server.contest.application.snapshot;

/** 파싱한 snapshot과 Importer가 읽은 파일 바이트의 SHA-256을 함께 전달한다. (SPEC §8.2, 결정-47) */
public record ContestSnapshotFile(ContestSnapshot snapshot, String snapshotSha256) {}

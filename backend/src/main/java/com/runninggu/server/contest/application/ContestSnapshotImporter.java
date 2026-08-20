package com.runninggu.server.contest.application;

import com.runninggu.server.contest.application.snapshot.ContestSnapshot;
import com.runninggu.server.contest.infrastructure.ContestSnapshotFileReader;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ContestSnapshotImporter {

    private final ContestSnapshotFileReader fileReader;
    private final ContestSnapshotImportService importService;

    public ContestSnapshotImporter(
            ContestSnapshotFileReader fileReader, ContestSnapshotImportService importService) {
        this.fileReader = fileReader;
        this.importService = importService;
    }

    public ContestSnapshotImportResult importFile(Path path) {
        ContestSnapshot snapshot = fileReader.read(path);
        return importService.importSnapshot(snapshot);
    }
}

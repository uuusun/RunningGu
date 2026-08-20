package com.runninggu.server.contest;

import com.runninggu.server.RunningGuServerApplication;
import com.runninggu.server.contest.application.ContestSnapshotImportResult;
import com.runninggu.server.contest.application.ContestSnapshotImporter;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class ContestSnapshotImporterApplication {

    private static final Logger log =
            LoggerFactory.getLogger(ContestSnapshotImporterApplication.class);
    private static final Path DEFAULT_SNAPSHOT_PATH = Path.of("data", "contest_snapshot.json");

    private ContestSnapshotImporterApplication() {}

    public static void main(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("사용법: contestImport [-PsnapshotPath=<snapshot 파일 경로>]");
        }
        Path snapshotPath = args.length == 1 ? Path.of(args[0]) : DEFAULT_SNAPSHOT_PATH;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        RunningGuServerApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {
            ContestSnapshotImportResult result =
                    context.getBean(ContestSnapshotImporter.class).importFile(snapshotPath);
            log.info(
                    "대회 snapshot 적재 완료: status={}, insertedContests={}, updatedContests={}, sources={}, events={}",
                    result.status(),
                    result.insertedContests(),
                    result.updatedContests(),
                    result.importedSources(),
                    result.importedEvents());
        }
    }
}

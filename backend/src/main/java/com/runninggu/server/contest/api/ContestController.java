package com.runninggu.server.contest.api;

import com.runninggu.server.contest.application.ContestQueryService;
import com.runninggu.server.contest.application.ContestSearchCondition;
import com.runninggu.server.contest.domain.ContestEventType;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contests")
public class ContestController {

    private final ContestQueryService queryService;

    public ContestController(ContestQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "대회 목록 조회")
    @GetMapping
    public ContestListResponse list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) List<ContestEventType> events,
            @RequestParam(required = false, defaultValue = "false") boolean openOnly,
            @RequestParam(required = false) List<String> regions,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        ContestSearchCondition condition = new ContestSearchCondition(
                query,
                events == null ? Set.of() : Set.copyOf(events),
                openOnly,
                regions == null ? Set.of() : Set.copyOf(regions),
                date);
        int pageSize = size == null ? ContestQueryService.DEFAULT_PAGE_SIZE : size;
        return ContestListResponse.from(queryService.findContests(condition, cursor, pageSize));
    }
}

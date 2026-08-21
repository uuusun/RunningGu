package com.runninggu.server.contest.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatusPolicy;
import com.runninggu.server.contest.domain.ContestSourceType;
import com.runninggu.server.contest.infrastructure.ContestQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContestQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final ContestQueryRepository queryRepository;
    private final ContestCursorCodec cursorCodec;
    private final Clock businessClock;

    public ContestQueryService(
            ContestQueryRepository queryRepository,
            ContestCursorCodec cursorCodec,
            Clock businessClock) {
        this.queryRepository = queryRepository;
        this.cursorCodec = cursorCodec;
        this.businessClock = businessClock;
    }

    /** 활성 상태인 오늘 이후 canonical 대회만 커서 페이지로 반환한다. (SPEC §4.5·§5.5) */
    public ContestListResult findContests(
            ContestSearchCondition condition,
            String encodedCursor,
            int size) {
        validateSize(size);
        LocalDate today = LocalDate.now(businessClock);
        ContestCursor cursor = cursorCodec.decode(encodedCursor);
        List<Contest> fetched = queryRepository.findPage(condition, cursor, size, today);
        boolean hasNext = fetched.size() > size;
        List<Contest> contests = hasNext ? fetched.subList(0, size) : fetched;

        List<Long> contestIds = contests.stream().map(Contest::getId).toList();
        Map<Long, List<ContestEventType>> events =
                queryRepository.findEventsByContestIds(contestIds);
        Map<Long, List<ContestSourceType>> sources =
                queryRepository.findActiveSourcesByContestIds(contestIds);
        List<ContestListItem> items = contests.stream()
                .map(contest -> toItem(
                        contest,
                        events.getOrDefault(contest.getId(), List.of()),
                        sources.getOrDefault(contest.getId(), List.of()),
                        today))
                .toList();

        String nextCursor = hasNext
                ? cursorCodec.encode(cursorOf(contests.getLast()))
                : null;
        return new ContestListResult(items, nextCursor, hasNext);
    }

    private ContestListItem toItem(
            Contest contest,
            List<ContestEventType> events,
            List<ContestSourceType> sources,
            LocalDate today) {
        return new ContestListItem(
                contest.getId(),
                contest.isActive(),
                contest.getName(),
                contest.getRegion(),
                contest.getPlace(),
                contest.getContestDate(),
                contest.getStartTime(),
                events,
                ContestRegistrationStatusPolicy.derive(
                        contest.getApplyStart(),
                        contest.getApplyEnd(),
                        contest.getSourceStatus(),
                        today),
                contest.getApplyStart(),
                contest.getApplyEnd(),
                contest.getImageUrl(),
                sources,
                contest.getCheckedAt(),
                // 인증·찜 SSOT 연결 전 공개 게스트 응답 계약이다. (API 명세 §3-1)
                false);
    }

    private ContestCursor cursorOf(Contest contest) {
        return new ContestCursor(contest.getContestDate(), contest.getId());
    }

    private void validateSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
    }
}

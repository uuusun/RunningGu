package com.runninggu.server.contest.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatusPolicy;
import com.runninggu.server.contest.domain.ContestSourceType;
import com.runninggu.server.contest.infrastructure.ContestQueryRepository;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContestQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_CLOSING_SOON_LIMIT = 4;
    public static final int MAX_CLOSING_SOON_LIMIT = 4;

    private final ContestQueryRepository queryRepository;
    private final ContestRepository contestRepository;
    private final ContestCursorCodec cursorCodec;
    private final Clock businessClock;

    public ContestQueryService(
            ContestQueryRepository queryRepository,
            ContestRepository contestRepository,
            ContestCursorCodec cursorCodec,
            Clock businessClock) {
        this.queryRepository = queryRepository;
        this.contestRepository = contestRepository;
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

        List<ContestListItem> items = toItems(contests, today);

        String nextCursor = hasNext
                ? cursorCodec.encode(cursorOf(contests.getLast()))
                : null;
        return new ContestListResult(items, nextCursor, hasNext);
    }

    /** 목록과 같은 공개 필터로 월간 캘린더 점 집계를 반환한다. (API 명세 §3-2) */
    public List<ContestDailyCount> findDailyCounts(
            int year,
            int month,
            ContestSearchCondition condition) {
        YearMonth yearMonth = toYearMonth(year, month);
        LocalDate today = LocalDate.now(businessClock);
        return queryRepository.findDailyCounts(condition, yearMonth, today);
    }

    /** 홈에 노출할 접수 마감 임박 대회를 최대 네 건 반환한다. (SPEC §4.4, API 명세 §3-3) */
    public List<ContestClosingSoonItem> findClosingSoon(int limit) {
        validateClosingSoonLimit(limit);
        LocalDate today = LocalDate.now(businessClock);
        return toItems(queryRepository.findClosingSoon(limit, today), today).stream()
                .map(item -> new ContestClosingSoonItem(
                        item,
                        Math.toIntExact(ChronoUnit.DAYS.between(today, item.applyEnd()))))
                .toList();
    }

    /** 비활성·과거 대회도 참조 보존을 위해 ID 상세로 반환한다. (SPEC 결정-46, API 명세 §3-4) */
    public ContestDetailItem findContest(long id) {
        Contest contest = contestRepository.findById(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONTEST_NOT_FOUND,
                        "대회 ID " + id + "를 찾을 수 없습니다."));
        LocalDate today = LocalDate.now(businessClock);
        ContestListItem item = toItems(List.of(contest), today).getFirst();
        int dDay = Math.toIntExact(ChronoUnit.DAYS.between(today, contest.getContestDate()));
        return new ContestDetailItem(
                item,
                contest.getOrganizer(),
                contest.getOfficialUrl(),
                contest.getLat(),
                contest.getLng(),
                dDay);
    }

    private List<ContestListItem> toItems(List<Contest> contests, LocalDate today) {
        List<Long> contestIds = contests.stream().map(Contest::getId).toList();
        Map<Long, List<ContestEventType>> events =
                queryRepository.findEventsByContestIds(contestIds);
        Map<Long, List<ContestSourceType>> sources =
                queryRepository.findActiveSourcesByContestIds(contestIds);
        return contests.stream()
                .map(contest -> toItem(
                        contest,
                        events.getOrDefault(contest.getId(), List.of()),
                        sources.getOrDefault(contest.getId(), List.of()),
                        today))
                .toList();
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

    private YearMonth toYearMonth(int year, int month) {
        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException exception) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "year와 month 값이 올바르지 않습니다.");
        }
    }

    private void validateClosingSoonLimit(int limit) {
        if (limit < 1 || limit > MAX_CLOSING_SOON_LIMIT) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "limit는 1 이상 " + MAX_CLOSING_SOON_LIMIT + " 이하여야 합니다.");
        }
    }
}

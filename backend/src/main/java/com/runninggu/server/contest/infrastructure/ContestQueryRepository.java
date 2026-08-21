package com.runninggu.server.contest.infrastructure;

import static com.runninggu.server.contest.domain.QContest.contest;
import static com.runninggu.server.contest.domain.QContestEvent.contestEvent;
import static com.runninggu.server.contest.domain.QContestSource.contestSource;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.runninggu.server.contest.application.ContestCursor;
import com.runninggu.server.contest.application.ContestDailyCount;
import com.runninggu.server.contest.application.ContestSearchCondition;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatus;
import com.runninggu.server.contest.domain.ContestSourceType;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/** 대회 목록과 후속 일별 집계가 공유할 검색 Predicate다. (API 명세 §3-1·부록 G-1) */
@Repository
public class ContestQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ContestQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public List<Contest> findPage(
            ContestSearchCondition condition,
            ContestCursor cursor,
            int size,
            LocalDate today) {
        BooleanBuilder where = filterPredicate(condition, today);
        if (cursor != null) {
            where.and(contest.contestDate.gt(cursor.contestDate())
                    .or(contest.contestDate.eq(cursor.contestDate())
                            .and(contest.id.gt(cursor.contestId()))));
        }

        return queryFactory
                .selectFrom(contest)
                .where(where)
                .orderBy(contest.contestDate.asc(), contest.id.asc())
                .limit(size + 1L)
                .fetch();
    }

    /** 목록과 동일한 공개 필터를 적용해 요청 월의 날짜별 대회 수를 센다. (API 명세 §3-2) */
    public List<ContestDailyCount> findDailyCounts(
            ContestSearchCondition condition,
            YearMonth yearMonth,
            LocalDate today) {
        BooleanBuilder where = filterPredicate(condition, today);
        where.and(contest.contestDate.between(
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth()));

        return queryFactory
                .select(contest.contestDate, contest.id.count())
                .from(contest)
                .where(where)
                .groupBy(contest.contestDate)
                .orderBy(contest.contestDate.asc())
                .fetch()
                .stream()
                .map(row -> new ContestDailyCount(
                        row.get(contest.contestDate),
                        row.get(contest.id.count())))
                .toList();
    }

    /** 활성 예정 대회 중 파생 접수상태가 OPEN이고 마감일이 있는 대회만 반환한다. (API 명세 §3-3) */
    public List<Contest> findClosingSoon(int limit, LocalDate today) {
        ContestSearchCondition openCondition = new ContestSearchCondition(
                null,
                Set.of(),
                true,
                Set.of(),
                null);
        BooleanBuilder where = filterPredicate(openCondition, today);
        where.and(contest.applyEnd.isNotNull());

        return queryFactory
                .selectFrom(contest)
                .where(where)
                .orderBy(
                        contest.applyEnd.asc(),
                        contest.contestDate.asc(),
                        contest.id.asc())
                .limit(limit)
                .fetch();
    }

    public Map<Long, List<ContestEventType>> findEventsByContestIds(
            Collection<Long> contestIds) {
        Map<Long, List<ContestEventType>> result = emptyListsByContestId(contestIds);
        if (contestIds.isEmpty()) {
            return result;
        }

        List<Tuple> rows = queryFactory
                .select(contestEvent.contest.id, contestEvent.eventType)
                .from(contestEvent)
                .where(contestEvent.contest.id.in(contestIds))
                .orderBy(contestEvent.contest.id.asc(), contestEvent.eventType.asc())
                .fetch();
        for (Tuple row : rows) {
            Long contestId = row.get(contestEvent.contest.id);
            ContestEventType eventType = row.get(contestEvent.eventType);
            result.get(contestId).add(eventType);
        }
        return immutableLists(result);
    }

    /** 감사용으로 남은 비활성 source는 제외하고 현재 승인 snapshot의 원천만 노출한다. (SPEC §8.2) */
    public Map<Long, List<ContestSourceType>> findActiveSourcesByContestIds(
            Collection<Long> contestIds) {
        Map<Long, List<ContestSourceType>> result = emptyListsByContestId(contestIds);
        if (contestIds.isEmpty()) {
            return result;
        }

        List<Tuple> rows = queryFactory
                .select(contestSource.contest.id, contestSource.sourceType)
                .distinct()
                .from(contestSource)
                .where(
                        contestSource.contest.id.in(contestIds),
                        contestSource.active.isTrue())
                .orderBy(contestSource.contest.id.asc(), contestSource.sourceType.asc())
                .fetch();
        for (Tuple row : rows) {
            Long contestId = row.get(contestSource.contest.id);
            ContestSourceType sourceType = row.get(contestSource.sourceType);
            result.get(contestId).add(sourceType);
        }
        return immutableLists(result);
    }

    private BooleanBuilder filterPredicate(
            ContestSearchCondition condition,
            LocalDate today) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(contest.active.isTrue());
        where.and(contest.contestDate.goe(today));

        if (StringUtils.hasText(condition.query())) {
            where.and(contest.name.contains(condition.query())
                    .or(contest.place.contains(condition.query()))
                    .or(contest.region.contains(condition.query())));
        }
        if (!condition.events().isEmpty()) {
            where.and(contest.id.in(JPAExpressions
                    .select(contestEvent.contest.id)
                    .from(contestEvent)
                    .where(contestEvent.eventType.in(condition.events()))));
        }
        if (condition.openOnly()) {
            where.and(openRegistrationPredicate(today));
        }
        if (!condition.regions().isEmpty()) {
            where.and(contest.region.in(condition.regions()));
        }
        if (condition.date() != null) {
            where.and(contest.contestDate.eq(condition.date()));
        }
        return where;
    }

    /** 응답의 `ContestRegistrationStatusPolicy`와 같은 §5.5 규칙을 SQL Predicate로 옮긴다. */
    private BooleanExpression openRegistrationPredicate(LocalDate today) {
        BooleanExpression registrationNotClosed =
                contest.applyEnd.isNull().or(contest.applyEnd.goe(today));
        BooleanExpression startedByDate =
                contest.applyStart.isNotNull().and(contest.applyStart.loe(today));
        BooleanExpression fallbackOpen = contest.applyStart.isNull()
                .and(contest.sourceStatus.eq(ContestRegistrationStatus.OPEN));
        return registrationNotClosed.and(startedByDate.or(fallbackOpen));
    }

    private <T> Map<Long, List<T>> emptyListsByContestId(Collection<Long> contestIds) {
        Map<Long, List<T>> result = new LinkedHashMap<>();
        for (Long contestId : contestIds) {
            result.put(contestId, new ArrayList<>());
        }
        return result;
    }

    private <T> Map<Long, List<T>> immutableLists(Map<Long, List<T>> source) {
        Map<Long, List<T>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }
}

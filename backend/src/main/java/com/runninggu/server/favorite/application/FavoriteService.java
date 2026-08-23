package com.runninggu.server.favorite.application;

import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.application.ContestQueryService;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.favorite.domain.Favorite;
import com.runninggu.server.favorite.infrastructure.FavoriteRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 찜 목록과 멱등 추가·해제를 현재 사용자 경계에서 처리한다. (API 명세 §7-C) */
@Service
@Transactional(readOnly = true)
public class FavoriteService {

    private static final int MAX_PAGE_SIZE = 50;

    private final FavoriteRepository favoriteRepository;
    private final AppUserRepository userRepository;
    private final ContestRepository contestRepository;
    private final ContestQueryService contestQueryService;
    private final Clock clock;

    public FavoriteService(
            FavoriteRepository favoriteRepository,
            AppUserRepository userRepository,
            ContestRepository contestRepository,
            ContestQueryService contestQueryService,
            Clock clock) {
        this.favoriteRepository = favoriteRepository;
        this.userRepository = userRepository;
        this.contestRepository = contestRepository;
        this.contestQueryService = contestQueryService;
        this.clock = clock;
    }

    public FavoriteListResult list(long userId, int pageNumber, int size) {
        validatePage(pageNumber, size);
        requireUser(userId);
        PageRequest pageable = PageRequest.of(
                pageNumber,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Favorite> page = favoriteRepository.findByUser_Id(userId, pageable);
        List<Contest> contests = page.getContent().stream()
                .map(Favorite::getContest)
                .toList();
        return new FavoriteListResult(
                contestQueryService.describeFavoriteContests(contests),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext());
    }

    @Transactional
    public void add(long userId, long contestId) {
        requireUser(userId);
        requireContest(contestId);
        favoriteRepository.insertIfAbsent(userId, contestId, clock.instant());
    }

    @Transactional
    public void remove(long userId, long contestId) {
        requireUser(userId);
        favoriteRepository.deleteByUserAndContest(userId, contestId);
    }

    private void requireUser(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
        }
    }

    private void requireContest(long contestId) {
        if (!contestRepository.existsById(contestId)) {
            throw new ApiException(
                    ErrorCode.CONTEST_NOT_FOUND,
                    "대회 ID " + contestId + "를 찾을 수 없습니다.");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "page는 0 이상, size는 1 이상 50 이하여야 합니다.");
        }
    }
}

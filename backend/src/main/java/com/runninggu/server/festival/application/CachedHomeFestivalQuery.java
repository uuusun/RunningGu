package com.runninggu.server.festival.application;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import com.runninggu.server.festival.domain.FestivalRegion;
import com.runninggu.server.festival.domain.HomeFestival;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class CachedHomeFestivalQuery {

    private static final Comparator<HomeFestival> DISPLAY_ORDER =
            Comparator.comparing(HomeFestival::inProgress)
                    .reversed()
                    .thenComparing(HomeFestival::startDate)
                    .thenComparing(HomeFestival::contentId);

    private final FestivalProvider festivalProvider;

    public CachedHomeFestivalQuery(FestivalProvider festivalProvider) {
        this.festivalProvider = festivalProvider;
    }

    /** 같은 월·KST 날짜의 성공 결과를 5분간 재사용한다. (API 명세 §0-5·§4-1) */
    @Cacheable(
            cacheNames = CacheConfig.HOME_FESTIVALS_CACHE,
            key = "#yearMonth + ':' + #today",
            sync = true)
    public List<HomeFestival> find(YearMonth yearMonth, LocalDate today) {
        LocalDate monthStart = yearMonth.atDay(1);
        LocalDate monthEnd = yearMonth.atEndOfMonth();
        return searchFestivals(monthStart).stream()
                .filter(festival -> overlaps(
                        festival.startDate(),
                        festival.endDate(),
                        monthStart,
                        monthEnd))
                .map(festival -> toHomeFestival(festival, today))
                .sorted(DISPLAY_ORDER)
                .toList();
    }

    private List<Festival> searchFestivals(LocalDate monthStart) {
        try {
            return festivalProvider.searchStartingFrom(monthStart);
        } catch (FestivalProviderException exception) {
            if (exception.reason() == Reason.TIMEOUT) {
                throw new ApiException(
                        ErrorCode.EXTERNAL_API_TIMEOUT,
                        "축제 조회 응답 시간이 초과됐습니다.");
            }
            throw new ApiException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "축제 조회를 완료하지 못했습니다.");
        }
    }

    /** 지역을 정규화하지 못해도 유효한 축제를 유지하고 빈 문자열을 반환한다. (API 명세 §4-1) */
    private HomeFestival toHomeFestival(Festival festival, LocalDate today) {
        String region = FestivalRegion.fromAddress(festival.address()).orElse("");
        return new HomeFestival(
                festival.contentId(),
                festival.name(),
                festival.startDate(),
                festival.endDate(),
                region,
                festival.imageUrl(),
                isInProgress(festival, today));
    }

    private boolean overlaps(
            LocalDate festivalStart,
            LocalDate festivalEnd,
            LocalDate monthStart,
            LocalDate monthEnd) {
        return !festivalStart.isAfter(monthEnd) && !festivalEnd.isBefore(monthStart);
    }

    private boolean isInProgress(Festival festival, LocalDate today) {
        return !festival.startDate().isAfter(today) && !festival.endDate().isBefore(today);
    }
}

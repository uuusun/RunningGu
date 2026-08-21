package com.runninggu.server.festival.application;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.festival.domain.Festival;
import com.runninggu.server.festival.domain.NearbyFestival;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NearbyFestivalService {

    static final int WINDOW_DAYS = 14;
    static final double MAX_DISTANCE_KM = 40.0;
    static final int MAX_RESULTS = 6;
    private static final double EARTH_RADIUS_KM = 6_371.0088;

    private final ContestRepository contestRepository;
    private final FestivalProvider festivalProvider;

    public NearbyFestivalService(
            ContestRepository contestRepository,
            FestivalProvider festivalProvider) {
        this.contestRepository = contestRepository;
        this.festivalProvider = festivalProvider;
    }

    /** 대회일 ±14일·반경 40km 축제를 거리순 여섯 건까지 반환한다. (SPEC §8.3) */
    @Cacheable(
            cacheNames = CacheConfig.NEARBY_FESTIVALS_CACHE,
            key = "#contestId",
            sync = true)
    public List<NearbyFestival> findNearby(long contestId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONTEST_NOT_FOUND,
                        "대회 ID " + contestId + "를 찾을 수 없습니다."));
        validateLocation(contest);

        LocalDate windowStart = contest.getContestDate().minusDays(WINDOW_DAYS);
        LocalDate windowEnd = contest.getContestDate().plusDays(WINDOW_DAYS);
        List<Festival> festivals = searchFestivals(windowStart);

        return festivals.stream()
                .filter(festival -> overlaps(
                        festival.startDate(),
                        festival.endDate(),
                        windowStart,
                        windowEnd))
                // 홈 월간 목록은 좌표 없는 축제도 쓰지만, 거리 계산에는 두 좌표가 모두 필요하다.
                .filter(Festival::hasCoordinates)
                .map(festival -> withDistance(contest, festival))
                .filter(festival -> festival.distanceKm() <= MAX_DISTANCE_KM)
                .sorted(Comparator.comparingDouble(NearbyFestival::distanceKm))
                .limit(MAX_RESULTS)
                .toList();
    }

    private List<Festival> searchFestivals(LocalDate windowStart) {
        try {
            return festivalProvider.searchStartingFrom(windowStart);
        } catch (FestivalProviderException exception) {
            if (exception.reason() == FestivalProviderException.Reason.TIMEOUT) {
                throw new ApiException(
                        ErrorCode.EXTERNAL_API_TIMEOUT,
                        "축제 조회 응답 시간이 초과됐습니다.");
            }
            throw new ApiException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "축제 조회를 완료하지 못했습니다.");
        }
    }

    private void validateLocation(Contest contest) {
        if (contest.getLat() == null || contest.getLng() == null) {
            throw new ApiException(
                    ErrorCode.CONTEST_LOCATION_UNAVAILABLE,
                    "대회장 좌표가 없어 인근 축제를 조회할 수 없습니다.");
        }
    }

    private boolean overlaps(
            LocalDate festivalStart,
            LocalDate festivalEnd,
            LocalDate windowStart,
            LocalDate windowEnd) {
        return !festivalStart.isAfter(windowEnd) && !festivalEnd.isBefore(windowStart);
    }

    private NearbyFestival withDistance(Contest contest, Festival festival) {
        double distanceKm = haversineKm(
                contest.getLat(),
                contest.getLng(),
                festival.lat(),
                festival.lng());
        return new NearbyFestival(
                festival.contentId(),
                festival.name(),
                festival.startDate(),
                festival.endDate(),
                distanceKm,
                festival.imageUrl(),
                festival.address());
    }

    static double haversineKm(
            BigDecimal fromLat,
            BigDecimal fromLng,
            BigDecimal toLat,
            BigDecimal toLng) {
        double fromLatRad = Math.toRadians(fromLat.doubleValue());
        double toLatRad = Math.toRadians(toLat.doubleValue());
        double latDelta = toLatRad - fromLatRad;
        double lngDelta = Math.toRadians(toLng.doubleValue() - fromLng.doubleValue());
        double haversine = Math.pow(Math.sin(latDelta / 2), 2)
                + Math.cos(fromLatRad)
                        * Math.cos(toLatRad)
                        * Math.pow(Math.sin(lngDelta / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(haversine));
    }
}

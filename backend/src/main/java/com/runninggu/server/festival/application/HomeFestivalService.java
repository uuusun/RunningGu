package com.runninggu.server.festival.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.festival.domain.HomeFestival;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HomeFestivalService {

    public static final int DEFAULT_SIZE = 6;
    public static final int MAX_SIZE = 20;
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM")
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.STRICT);

    private final CachedHomeFestivalQuery cachedQuery;
    private final Clock businessClock;

    public HomeFestivalService(
            CachedHomeFestivalQuery cachedQuery,
            Clock businessClock) {
        this.cachedQuery = cachedQuery;
        this.businessClock = businessClock;
    }

    /** 요청 월과 겹치는 전국 축제를 홈 노출 순서로 반환한다. (SPEC §4.4, API 명세 §4-1) */
    public List<HomeFestival> find(String requestedYearMonth, Integer requestedSize) {
        YearMonth yearMonth = requestedYearMonth == null
                ? YearMonth.now(businessClock)
                : parseYearMonth(requestedYearMonth);
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        validateSize(size);
        return cachedQuery.find(yearMonth, LocalDate.now(businessClock)).stream()
                .limit(size)
                .toList();
    }

    private YearMonth parseYearMonth(String value) {
        try {
            return YearMonth.parse(value, YEAR_MONTH_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "yearMonth는 YYYY-MM 형식이어야 합니다.");
        }
    }

    private void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
    }
}

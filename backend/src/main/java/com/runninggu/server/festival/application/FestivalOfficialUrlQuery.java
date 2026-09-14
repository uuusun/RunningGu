package com.runninggu.server.festival.application;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 목록 응답에 붙일 축제 공식 페이지 주소. (API 명세 §3-5·§4-1 `officialUrl`)
 *
 * **링크 하나 때문에 목록을 실패시키지 않는다.** 조회가 실패하면 그 축제만 `officialUrl` 없이
 * 내려간다. 캐시는 {@link CachedFestivalOfficialUrlQuery} 에 있다 — 한 클래스 안에서 자기
 * 메서드를 부르면 캐시 프록시를 타지 않아 둘로 나눴다.
 *
 * ## 전체 시간 예산이 있다 (#352 리뷰)
 *
 * 노출분(홈 최대 20건 · 인근 6건)을 **한꺼번에** 부르고 전체를 {@link #BUDGET} 안에 끝낸다.
 * 차례로 부르면 KTO read timeout(2.5초) × 건수라 6건만 늦어도 15초 — 앱 read timeout 과 같아
 * 축제 영역 전체가 오류로 보인다. 예산 안에 안 온 건 그 요청에서만 null 이고, 뒤에서 끝난
 * 조회는 캐시에 들어가 다음 요청이 바로 쓴다 — 그래서 늦은 작업을 **취소하지 않는다.**
 */
@Service
public class FestivalOfficialUrlQuery {

    /** 목록 한 번에 공식 페이지 조회에 쓸 수 있는 전체 시간. 앱 read timeout 15초보다 확실히 짧게. */
    static final Duration BUDGET = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(FestivalOfficialUrlQuery.class);

    private final CachedFestivalOfficialUrlQuery cachedQuery;
    private final ExecutorService executor;

    @Autowired
    public FestivalOfficialUrlQuery(CachedFestivalOfficialUrlQuery cachedQuery) {
        this(cachedQuery, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 테스트용 — 예산 검증에 executor 를 바꿔 끼운다. */
    FestivalOfficialUrlQuery(CachedFestivalOfficialUrlQuery cachedQuery, ExecutorService executor) {
        this.cachedQuery = cachedQuery;
        this.executor = executor;
    }

    /**
     * contentId → 공식 페이지. 없거나 못 가져왔거나 예산 안에 안 왔으면 값이 null 이다.
     * 순서는 입력 순서를 지킨다. 같은 id 가 두 번 오면 한 번만 조회한다.
     */
    public Map<String, String> findAll(Collection<String> contentIds) {
        Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        for (String contentId : contentIds) {
            futures.computeIfAbsent(
                    contentId,
                    id -> CompletableFuture.supplyAsync(() -> findOrNull(id), executor));
        }

        long deadlineNanos = System.nanoTime() + BUDGET.toNanos();
        Map<String, String> result = new HashMap<>();
        int late = 0;
        for (Map.Entry<String, CompletableFuture<String>> entry : futures.entrySet()) {
            result.put(entry.getKey(), awaitUntil(entry.getValue(), deadlineNanos));
            if (result.get(entry.getKey()) == null && !entry.getValue().isDone()) {
                late++;
            }
        }
        if (late > 0) {
            log.warn("축제 공식 페이지 조회 {}건이 예산 {}ms 안에 오지 않아 이번 응답에서 비웠습니다.",
                    late, BUDGET.toMillis());
        }
        return result;
    }

    /** 없거나 못 가져오면 `null`. */
    public String findOrNull(String contentId) {
        try {
            return cachedQuery.find(contentId).url();
        } catch (FestivalProviderException exception) {
            log.warn("축제 공식 페이지 조회에 실패했습니다. reason={}", exception.reason());
            return null;
        }
    }

    private String awaitUntil(CompletableFuture<String> future, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        try {
            return future.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // 취소하지 않는다 — 끝나면 캐시에 들어가 다음 요청이 쓴다
            return null;
        } catch (ExecutionException exception) {
            // 예외 원문은 로그에 남기지 않는다 (LogPrivacySourceTest · AGENTS 8장). 실제 원인은
            // findOrNull 이 이미 reason 으로 남겼다 — 여기 오는 건 그 밖의 예상 못 한 예외뿐이다
            log.warn("축제 공식 페이지 조회 작업이 예상하지 못한 예외로 끝났습니다.");
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}

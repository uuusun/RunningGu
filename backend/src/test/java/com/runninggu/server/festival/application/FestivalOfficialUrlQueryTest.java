package com.runninggu.server.festival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.runninggu.server.festival.application.CachedFestivalOfficialUrlQuery.OfficialUrl;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FestivalOfficialUrlQueryTest {

    @Mock
    private CachedFestivalOfficialUrlQuery cachedQuery;

    private ExecutorService executor;
    private FestivalOfficialUrlQuery query;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        query = new FestivalOfficialUrlQuery(cachedQuery, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 있음_없음_실패를_각각_값_null_null로_돌려주고_목록은_살린다() {
        given(cachedQuery.find("ok")).willReturn(new OfficialUrl("https://ok.test"));
        given(cachedQuery.find("none")).willReturn(new OfficialUrl(null));
        given(cachedQuery.find("boom")).willThrow(new FestivalProviderException(Reason.ERROR));

        Map<String, String> result = query.findAll(List.of("ok", "none", "boom", "ok"));

        assertThat(result).hasSize(3);
        assertThat(result.get("ok")).isEqualTo("https://ok.test");
        assertThat(result.get("none")).isNull();
        assertThat(result.get("boom")).isNull();
    }

    @Test
    void 예산_안에_오지_않은_조회는_이번_응답에서만_null이고_취소하지_않는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        given(cachedQuery.find("fast")).willReturn(new OfficialUrl("https://fast.test"));
        given(cachedQuery.find("slow")).willAnswer(invocation -> {
            release.await();
            return new OfficialUrl("https://slow.test");
        });

        long started = System.nanoTime();
        Map<String, String> result = query.findAll(List.of("fast", "slow"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        release.countDown();

        // 느린 한 건이 빠른 건을 막지 않고, 전체가 예산(3초) 근처에서 끝난다
        assertThat(result.get("fast")).isEqualTo("https://fast.test");
        assertThat(result.get("slow")).isNull();
        assertThat(elapsedMs).isBetween(
                FestivalOfficialUrlQuery.BUDGET.toMillis() - 200,
                FestivalOfficialUrlQuery.BUDGET.toMillis() + 1_500);
    }

    @Test
    void 빈_입력은_외부를_부르지_않는다() {
        assertThat(query.findAll(List.of())).isEmpty();
        verifyNoInteractions(cachedQuery);
    }
}

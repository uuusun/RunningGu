package com.runninggu.server.festival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.runninggu.server.festival.domain.NearbyFestival;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NearbyFestivalServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    @Mock
    private CachedNearbyFestivalQuery cachedQuery;

    @Mock
    private FestivalOfficialUrlQuery officialUrlQuery;

    @InjectMocks
    private NearbyFestivalService service;

    @Test
    void 캐시된_기본_목록에_공식_페이지를_한꺼번에_붙이고_없으면_null로_둔다() {
        given(cachedQuery.findNearby(1L)).willReturn(List.of(
                festival("with-site"),
                festival("no-site")));
        Map<String, String> urls = new HashMap<>();
        urls.put("with-site", "https://with-site.test");
        urls.put("no-site", null);
        given(officialUrlQuery.findAll(List.of("with-site", "no-site"))).willReturn(urls);

        assertThat(service.findNearby(1L))
                .extracting(NearbyFestival::contentId, NearbyFestival::officialUrl)
                .containsExactly(
                        tuple("with-site", "https://with-site.test"),
                        tuple("no-site", null));
    }

    private NearbyFestival festival(String contentId) {
        return new NearbyFestival(contentId, "축제 " + contentId, DATE, DATE, 1.0, null, "", null);
    }
}

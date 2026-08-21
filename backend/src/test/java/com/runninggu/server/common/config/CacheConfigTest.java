package com.runninggu.server.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;

class CacheConfigTest {

    @Test
    void 지오코드_캐시는_최대_500건을_5분간_보관한다() {
        var cacheManager = new CacheConfig().cacheManager();
        var springCache = (CaffeineCache) cacheManager.getCache(CacheConfig.GEOCODE_CACHE);
        @SuppressWarnings("unchecked")
        Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();

        assertThat(nativeCache.policy()
                        .eviction()
                        .orElseThrow()
                        .getMaximum())
                .isEqualTo(500);
        assertThat(nativeCache.policy()
                        .expireAfterWrite()
                        .orElseThrow()
                        .getExpiresAfter(TimeUnit.MINUTES))
                .isEqualTo(5);
    }
}

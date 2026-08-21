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

    @Test
    void 홈_축제_캐시는_월별_결과를_5분간_보관한다() {
        var cacheManager = new CacheConfig().cacheManager();
        var springCache =
                (CaffeineCache) cacheManager.getCache(CacheConfig.HOME_FESTIVALS_CACHE);
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

    @Test
    void 인근_축제_캐시는_대회별_최대_500건을_하루간_보관한다() {
        var cacheManager = new CacheConfig().cacheManager();
        var springCache =
                (CaffeineCache) cacheManager.getCache(CacheConfig.NEARBY_FESTIVALS_CACHE);
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
                        .getExpiresAfter(TimeUnit.HOURS))
                .isEqualTo(24);
    }
}

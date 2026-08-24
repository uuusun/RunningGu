package com.runninggu.server.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 단일 서버 MVP의 외부 API TTL 캐시는 Caffeine을 사용한다. (SPEC §9.2) */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String GEOCODE_CACHE = "geocode";
    public static final String HOME_FESTIVALS_CACHE = "homeFestivals";
    public static final String NEARBY_FESTIVALS_CACHE = "nearbyFestivals";
    public static final String POI_CACHE = "poi";
    public static final String WALKING_SPOTS_CACHE = "walkingSpots";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setAllowNullValues(false);
        cacheManager.registerCustomCache(
                GEOCODE_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build());
        cacheManager.registerCustomCache(
                HOME_FESTIVALS_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build());
        cacheManager.registerCustomCache(
                NEARBY_FESTIVALS_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofDays(1))
                        .build());
        cacheManager.registerCustomCache(
                POI_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(2_000)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build());
        cacheManager.registerCustomCache(
                WALKING_SPOTS_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(2_000)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build());
        return cacheManager;
    }
}

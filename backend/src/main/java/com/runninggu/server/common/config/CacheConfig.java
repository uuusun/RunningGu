package com.runninggu.server.common.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** 단일 서버 MVP의 외부 API TTL 캐시는 Caffeine을 사용한다. (SPEC §9.2) */
@Configuration
@EnableCaching
public class CacheConfig {}

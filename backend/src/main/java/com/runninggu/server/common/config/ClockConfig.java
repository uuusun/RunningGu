package com.runninggu.server.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 비즈니스 날짜 판정은 기기나 서버 기본 시간대와 무관하게 KST를 사용한다. (SPEC §6.6) */
@Configuration
public class ClockConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock businessClock() {
        return Clock.system(KST);
    }
}

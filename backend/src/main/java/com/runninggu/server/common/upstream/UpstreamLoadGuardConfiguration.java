package com.runninggu.server.common.upstream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UpstreamLoadGuardProperties.class)
public class UpstreamLoadGuardConfiguration {

    @Bean
    public UpstreamLoadGuard upstreamLoadGuard(UpstreamLoadGuardProperties properties) {
        return new UpstreamLoadGuard(properties);
    }
}

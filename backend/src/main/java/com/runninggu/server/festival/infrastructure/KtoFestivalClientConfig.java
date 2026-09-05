package com.runninggu.server.festival.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamGuardedClientHttpRequestFactory;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamProvider;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KtoFestivalProperties.class)
public class KtoFestivalClientConfig {

    @Bean
    public KtoFestivalClient ktoFestivalClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            KtoFestivalProperties properties,
            UpstreamLoadGuard upstreamLoadGuard) {
        UpstreamGuardedClientHttpRequestFactory requestFactory =
                new UpstreamGuardedClientHttpRequestFactory(upstreamLoadGuard);
        requestFactory.setConnectTimeout(toMillis(properties.connectTimeout()));
        requestFactory.setReadTimeout(toMillis(properties.readTimeout()));

        RestClient restClient = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(new UpstreamLoadGuardInterceptor(
                        upstreamLoadGuard,
                        UpstreamProvider.KTO))
                .build();
        return new KtoFestivalClient(
                restClient,
                objectMapper,
                properties.serviceKey(),
                upstreamLoadGuard);
    }

    private int toMillis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }
}

package com.runninggu.server.poi.infrastructure;

import com.runninggu.server.geocode.infrastructure.KakaoLocalProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class KakaoPoiClientConfig {

    @Bean
    public KakaoPoiClient kakaoPoiClient(
            RestClient.Builder builder,
            KakaoLocalProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = requestFactory(
                properties.connectTimeout(),
                properties.readTimeout());
        RestClient restClient = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new KakaoPoiClient(restClient, properties.restKey());
    }

    private SimpleClientHttpRequestFactory requestFactory(
            Duration connectTimeout,
            Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        return requestFactory;
    }
}

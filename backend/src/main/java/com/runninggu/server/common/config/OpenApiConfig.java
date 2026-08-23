package com.runninggu.server.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실행 중인 springdoc 문서를 최종 HTTP 계약으로 사용한다. (SPEC 결정-18) */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI runningGuOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("런닝구 API")
                        .version("v3.1")
                        .description("런닝구 Android 앱용 백엔드 API"))
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}

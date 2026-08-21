package com.runninggu.server.common.config;

import com.runninggu.server.common.error.ApiProblemWriter;
import com.runninggu.server.common.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 모바일 API는 무상태로 동작하며 공개·인증 경로는 기능 구현 시 명시적으로 연다. (SPEC §9.2~9.3) */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final String[] OPENAPI_PATHS = {
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiProblemWriter problemWriter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(OPENAPI_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/contests").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/contests/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/contests/*/festivals").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/geocode").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pois").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, cause) ->
                                problemWriter.write(request, response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, cause) ->
                                problemWriter.write(request, response, ErrorCode.FORBIDDEN)))
                .anonymous(Customizer.withDefaults());

        return http.build();
    }
}

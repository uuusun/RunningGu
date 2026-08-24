package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.LoginAttemptRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 본문 검증 전 모든 이메일 로그인 요청의 IP 제한을 적용한다. (SPEC §4.1, 결정-55) */
@Configuration
@ConditionalOnBean(LoginAttemptRateLimiter.class)
public class LoginRateLimitWebConfig implements WebMvcConfigurer {

    private final LoginAttemptRateLimiter loginAttemptRateLimiter;

    public LoginRateLimitWebConfig(LoginAttemptRateLimiter loginAttemptRateLimiter) {
        this.loginAttemptRateLimiter = loginAttemptRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler) {
                        if ("POST".equals(request.getMethod())) {
                            loginAttemptRateLimiter.checkIp(request.getRemoteAddr());
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/auth/login");
    }
}

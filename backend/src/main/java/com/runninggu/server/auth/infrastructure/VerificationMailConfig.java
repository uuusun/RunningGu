package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.VerificationMailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(VerificationMailProperties.class)
public class VerificationMailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "runninggu.mail", name = "enabled", havingValue = "true")
    VerificationMailSender smtpVerificationMailSender(
            JavaMailSender javaMailSender,
            VerificationMailProperties properties) {
        return new SpringMailVerificationMailSender(javaMailSender, properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "runninggu.mail",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    VerificationMailSender disabledVerificationMailSender() {
        return new VerificationMailSender() {
            @Override
            public void sendSignupCode(String recipient, String code) {
                throw disabled();
            }

            @Override
            public void sendPasswordResetLink(String recipient, String rawToken) {
                throw disabled();
            }

            private MailDeliveryException disabled() {
                return new MailDeliveryException("메일 발송 기능이 비활성화되어 있습니다.");
            }
        };
    }
}

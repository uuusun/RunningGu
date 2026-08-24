package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.VerificationMailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SpringMailVerificationMailSender implements VerificationMailSender {

    private static final String SIGNUP_SUBJECT = "[런닝구] 이메일 인증 코드";
    private static final String PASSWORD_RESET_SUBJECT = "[런닝구] 비밀번호 재설정";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final String passwordResetUrl;

    public SpringMailVerificationMailSender(
            JavaMailSender mailSender,
            VerificationMailProperties properties) {
        this.mailSender = mailSender;
        this.fromAddress = requireText(properties.fromAddress(), "SMTP_FROM_ADDRESS");
        this.fromName = requireText(properties.fromName(), "SMTP_FROM_NAME");
        this.passwordResetUrl = requireHttpUrl(
                properties.passwordResetUrl(),
                "PASSWORD_RESET_URL");
    }

    @Override
    public void sendSignupCode(String recipient, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(
                    fromAddress,
                    fromName,
                    StandardCharsets.UTF_8.name()));
            helper.setTo(recipient);
            helper.setSubject(SIGNUP_SUBJECT);
            helper.setText(signupBody(code), false);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new MailDeliveryException("인증 메일을 발송하지 못했습니다.", exception);
        }
    }

    @Override
    public void sendPasswordResetLink(String recipient, String rawToken) {
        String link = UriComponentsBuilder.fromUriString(passwordResetUrl)
                .queryParam("token", rawToken)
                .build()
                .encode()
                .toUriString();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(
                    fromAddress,
                    fromName,
                    StandardCharsets.UTF_8.name()));
            helper.setTo(recipient);
            helper.setSubject(PASSWORD_RESET_SUBJECT);
            helper.setText(passwordResetBody(link), false);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new MailDeliveryException("비밀번호 재설정 메일을 발송하지 못했습니다.", exception);
        }
    }

    private String signupBody(String code) {
        return """
                런닝구 이메일 인증 코드입니다.

                %s

                인증 코드는 10분 동안 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(code).stripTrailing();
    }

    private String passwordResetBody(String link) {
        return """
                런닝구 비밀번호 재설정 링크입니다.

                %s

                링크는 30분 동안 한 번만 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(link).stripTrailing();
    }

    private String requireText(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " 값이 필요합니다.");
        }
        return value;
    }

    private String requireHttpUrl(String value, String environmentName) {
        String url = requireText(value, environmentName);
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("HTTP(S) 절대 URL이 아닙니다.");
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    environmentName + " 값은 HTTP(S) 절대 URL이어야 합니다.",
                    exception);
        }
    }
}

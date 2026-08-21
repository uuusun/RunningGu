package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.VerificationMailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SpringMailVerificationMailSender implements VerificationMailSender {

    private static final String SUBJECT = "[런닝구] 이메일 인증 코드";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public SpringMailVerificationMailSender(
            JavaMailSender mailSender,
            VerificationMailProperties properties) {
        this.mailSender = mailSender;
        this.fromAddress = requireText(properties.fromAddress(), "SMTP_FROM_ADDRESS");
        this.fromName = requireText(properties.fromName(), "SMTP_FROM_NAME");
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
            helper.setSubject(SUBJECT);
            helper.setText(body(code), false);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new MailDeliveryException("인증 메일을 발송하지 못했습니다.", exception);
        }
    }

    private String body(String code) {
        return """
                런닝구 이메일 인증 코드입니다.

                %s

                인증 코드는 10분 동안 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(code).stripTrailing();
    }

    private String requireText(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " 값이 필요합니다.");
        }
        return value;
    }
}

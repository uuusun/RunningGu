package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class SpringMailVerificationMailSenderTest {

    @Test
    void UTF8_일반텍스트로_승인된_제목과_내용을_발송한다() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        SpringMailVerificationMailSender sender = new SpringMailVerificationMailSender(
                javaMailSender,
                new VerificationMailProperties(true, "noreply@runninggu.example", "런닝구"));

        sender.sendSignupCode("runner@example.com", "001234");
        message.saveChanges();

        verify(javaMailSender).send(message);
        assertThat(message.getSubject()).isEqualTo("[런닝구] 이메일 인증 코드");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("runner@example.com");
        assertThat(message.getFrom()[0].toString()).contains("noreply@runninggu.example");
        assertThat(message.getContentType()).contains("text/plain").contains("UTF-8");
        assertThat(new String(message.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains("001234")
                .contains("10분")
                .contains("본인이 요청하지 않았다면");
    }

    @Test
    void SMTP_오류는_메일_전용_예외로_변환한다() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("smtp unavailable"))
                .when(javaMailSender)
                .send(message);
        SpringMailVerificationMailSender sender = new SpringMailVerificationMailSender(
                javaMailSender,
                new VerificationMailProperties(true, "noreply@runninggu.example", "런닝구"));

        assertThatThrownBy(() -> sender.sendSignupCode("runner@example.com", "001234"))
                .isInstanceOf(MailDeliveryException.class)
                .hasCauseInstanceOf(MailSendException.class);
    }

    @Test
    void 발신주소는_기동시점에_필수값으로_검증한다() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);

        assertThatThrownBy(() -> new SpringMailVerificationMailSender(
                        javaMailSender,
                        new VerificationMailProperties(true, " ", "런닝구")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_FROM_ADDRESS");
    }
}

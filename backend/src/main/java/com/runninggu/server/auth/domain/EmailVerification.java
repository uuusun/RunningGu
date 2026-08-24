package com.runninggu.server.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(
        name = "email_verification",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_email_verification_email_purpose",
                    columnNames = {"email", "purpose"})
        })
public class EmailVerification {

    public static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    public static final Duration PASSWORD_RESET_VALIDITY = Duration.ofMinutes(30);
    public static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    public static final Duration VERIFIED_VALIDITY = Duration.ofMinutes(30);
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmailVerificationPurpose purpose;

    @Column(name = "code_hash", length = 255)
    private String codeHash;

    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected EmailVerification() {}

    public static EmailVerification signup(String email, String codeHash, Instant sentAt) {
        EmailVerification verification = new EmailVerification();
        verification.email = email;
        verification.purpose = EmailVerificationPurpose.SIGNUP;
        verification.reissue(codeHash, sentAt);
        return verification;
    }

    public static EmailVerification passwordReset(
            String email,
            String tokenHash,
            Instant sentAt) {
        EmailVerification verification = new EmailVerification();
        verification.email = email;
        verification.purpose = EmailVerificationPurpose.PASSWORD_RESET;
        verification.reissuePasswordReset(tokenHash, sentAt);
        return verification;
    }

    /** 재발송은 이전 코드·실패 횟수·인증 상태를 모두 무효화한다. (SPEC §4.2, 결정-50) */
    public void reissue(String codeHash, Instant sentAt) {
        this.codeHash = codeHash;
        this.tokenHash = null;
        this.attempts = 0;
        this.sentAt = sentAt;
        this.expiresAt = sentAt.plus(CODE_VALIDITY);
        this.verifiedAt = null;
        this.consumedAt = null;
    }

    /** 재발송은 이전 비밀번호 재설정 링크를 즉시 무효화한다. (SPEC §4.3, 결정-6) */
    public void reissuePasswordReset(String tokenHash, Instant sentAt) {
        if (purpose != EmailVerificationPurpose.PASSWORD_RESET) {
            throw new IllegalStateException("PASSWORD_RESET 인증만 재발급할 수 있습니다.");
        }
        this.codeHash = null;
        this.tokenHash = tokenHash;
        this.attempts = 0;
        this.sentAt = sentAt;
        this.expiresAt = sentAt.plus(PASSWORD_RESET_VALIDITY);
        this.verifiedAt = null;
        this.consumedAt = null;
    }

    public boolean isInSendCooldown(Instant now) {
        return now.isBefore(sentAt.plus(SEND_COOLDOWN));
    }

    public boolean isCodeExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isPasswordResetActive(Instant now) {
        return purpose == EmailVerificationPurpose.PASSWORD_RESET
                && consumedAt == null
                && now.isBefore(expiresAt);
    }

    public boolean isVerifiedAndActive(Instant now) {
        return verifiedAt != null
                && consumedAt == null
                && now.isBefore(verifiedAt.plus(VERIFIED_VALIDITY));
    }

    public boolean isLocked() {
        return attempts >= MAX_ATTEMPTS;
    }

    public int registerFailure() {
        attempts = Math.min(MAX_ATTEMPTS, attempts + 1);
        return attempts;
    }

    public void markVerified(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    /** 가입 트랜잭션 안에서 인증 자격을 한 번만 소비한다. (SPEC §4.2) */
    public void markConsumed(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public EmailVerificationPurpose getPurpose() {
        return purpose;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}

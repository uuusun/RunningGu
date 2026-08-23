package com.runninggu.server.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "login_identity",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_login_identity_provider_subject",
                    columnNames = {"provider", "provider_subject"})
        })
public class LoginIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LoginProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 320)
    private String providerSubject;

    @Column(name = "email_snapshot", length = 320)
    private String emailSnapshot;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected LoginIdentity() {}

    public static LoginIdentity email(
            AppUser user,
            String email,
            String passwordHash,
            Instant verifiedAt,
            Instant createdAt) {
        LoginIdentity identity = new LoginIdentity();
        identity.user = user;
        identity.provider = LoginProvider.EMAIL;
        identity.providerSubject = email;
        identity.emailSnapshot = null;
        identity.passwordHash = passwordHash;
        identity.emailVerifiedAt = verifiedAt;
        identity.createdAt = createdAt;
        identity.lastLoginAt = createdAt;
        return identity;
    }

    public void markLoggedIn(Instant now) {
        lastLoginAt = now;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public LoginProvider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getEmailSnapshot() {
        return emailSnapshot;
    }
}

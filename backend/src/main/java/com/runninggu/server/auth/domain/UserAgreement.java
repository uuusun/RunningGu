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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "user_agreement")
public class UserAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_type", nullable = false, length = 32)
    private AgreementType type;

    @Column(nullable = false, length = 32)
    private String version;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected UserAgreement() {}

    public static UserAgreement record(
            AppUser user,
            AgreementType type,
            String version,
            boolean agreed,
            Instant changedAt) {
        UserAgreement agreement = new UserAgreement();
        agreement.user = user;
        agreement.type = type;
        agreement.version = version;
        agreement.agreed = agreed;
        agreement.changedAt = changedAt;
        return agreement;
    }
}

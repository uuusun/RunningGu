package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.auth.infrastructure.EmailVerificationRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationCleanupTransaction {

    private final EmailVerificationRepository repository;

    public EmailVerificationCleanupTransaction(EmailVerificationRepository repository) {
        this.repository = repository;
    }

    /** 인증 종류별 만료 기준을 한 번의 멱등 쿼리로 적용한다. (SPEC §6.5, 결정-57) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanup(Instant cutoff) {
        return repository.deleteExpired(
                cutoff,
                cutoff.minus(EmailVerification.VERIFIED_VALIDITY));
    }
}

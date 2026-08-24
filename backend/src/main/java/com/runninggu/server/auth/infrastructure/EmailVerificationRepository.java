package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.auth.domain.EmailVerificationPurpose;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findByEmailAndPurpose(
            String email,
            EmailVerificationPurpose purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<EmailVerification> findAllByTokenHash(String tokenHash);

    void deleteAllByEmail(String email);
}

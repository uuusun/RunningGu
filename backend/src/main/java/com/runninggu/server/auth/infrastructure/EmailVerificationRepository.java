package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.auth.domain.EmailVerificationPurpose;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findByEmailAndPurpose(
            String email,
            EmailVerificationPurpose purpose);

    @Query("SELECT verification.email FROM EmailVerification verification "
            + "WHERE verification.tokenHash = :tokenHash")
    List<String> findEmailsByTokenHash(@Param("tokenHash") String tokenHash);

    void deleteAllByEmail(String email);

    /** 이메일 인증과 재설정 기록을 각 계약 만료시각으로 멱등 정리한다. (SPEC §6.5, 결정-57) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM email_verification
            WHERE consumed_at IS NOT NULL
               OR (purpose = 'SIGNUP'
                   AND verified_at IS NULL
                   AND expires_at <= :cutoff)
               OR (purpose = 'SIGNUP'
                   AND verified_at IS NOT NULL
                   AND verified_at <= :verifiedCutoff)
               OR (purpose = 'PASSWORD_RESET'
                   AND expires_at <= :cutoff)
            """, nativeQuery = true)
    int deleteExpired(
            @Param("cutoff") java.time.Instant cutoff,
            @Param("verifiedCutoff") java.time.Instant verifiedCutoff);
}

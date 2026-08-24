package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RefreshToken> findAllByFamilyIdAndRevokedAtIsNull(UUID familyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNull(Long userId);
}

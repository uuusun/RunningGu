package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RefreshToken> findAllByFamilyIdAndRevokedAtIsNull(UUID familyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNull(Long userId);

    /** 탈퇴 시 모든 세션 행을 id 순서로 잠그고 삭제한다. (SPEC §6.5, 결정-57) */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            WITH targets AS MATERIALIZED (
                SELECT id
                FROM refresh_token
                WHERE user_id = :userId
                ORDER BY id
                FOR UPDATE
            )
            DELETE FROM refresh_token token
            USING targets
            WHERE token.id = targets.id
            """, nativeQuery = true)
    int deleteAllByUserIdInIdOrder(@Param("userId") Long userId);

    /** 원래 만료시각을 지난 활성·폐기 토큰을 함께 정리한다. (SPEC §6.5, 결정-57) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            WITH expired AS MATERIALIZED (
                SELECT id
                FROM refresh_token
                WHERE expires_at <= :cutoff
                ORDER BY id
                FOR UPDATE
            )
            DELETE FROM refresh_token token
            USING expired
            WHERE token.id = expired.id
            """, nativeQuery = true)
    int deleteExpired(@Param("cutoff") java.time.Instant cutoff);
}

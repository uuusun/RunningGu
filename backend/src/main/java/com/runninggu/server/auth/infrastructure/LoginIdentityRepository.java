package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginIdentityRepository extends JpaRepository<LoginIdentity, Long> {
    boolean existsByProviderAndProviderSubject(LoginProvider provider, String providerSubject);

    @EntityGraph(attributePaths = "user")
    Optional<LoginIdentity> findByProviderAndProviderSubject(
            LoginProvider provider,
            String providerSubject);

    @EntityGraph(attributePaths = "user")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT identity
            FROM LoginIdentity identity
            WHERE identity.provider = :provider
              AND identity.providerSubject = :providerSubject
            """)
    Optional<LoginIdentity> findByProviderAndProviderSubjectForUpdate(
            @Param("provider") LoginProvider provider,
            @Param("providerSubject") String providerSubject);

    @EntityGraph(attributePaths = "user")
    Optional<LoginIdentity> findByUser_Id(Long userId);

    @EntityGraph(attributePaths = "user")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT identity FROM LoginIdentity identity WHERE identity.user.id = :userId")
    Optional<LoginIdentity> findByUserIdForUpdate(@Param("userId") Long userId);
}

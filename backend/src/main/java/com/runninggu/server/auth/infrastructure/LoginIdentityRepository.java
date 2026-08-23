package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface LoginIdentityRepository extends JpaRepository<LoginIdentity, Long> {
    boolean existsByProviderAndProviderSubject(LoginProvider provider, String providerSubject);

    @EntityGraph(attributePaths = "user")
    Optional<LoginIdentity> findByProviderAndProviderSubject(
            LoginProvider provider,
            String providerSubject);

    @EntityGraph(attributePaths = "user")
    Optional<LoginIdentity> findByUser_Id(Long userId);
}

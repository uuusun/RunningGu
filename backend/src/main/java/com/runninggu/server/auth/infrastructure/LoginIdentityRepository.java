package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginIdentityRepository extends JpaRepository<LoginIdentity, Long> {
    boolean existsByProviderAndProviderSubject(LoginProvider provider, String providerSubject);
}

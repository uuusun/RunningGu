package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    boolean existsByNicknameKey(String nicknameKey);
}

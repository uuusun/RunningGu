package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.AppUser;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    boolean existsByNicknameKey(String nicknameKey);

    boolean existsByNicknameKeyAndIdNot(String nicknameKey, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT appUser FROM AppUser appUser WHERE appUser.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);
}

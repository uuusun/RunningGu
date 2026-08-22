package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {}

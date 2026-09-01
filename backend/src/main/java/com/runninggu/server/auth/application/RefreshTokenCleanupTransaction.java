package com.runninggu.server.auth.application;

import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenCleanupTransaction {

    private final RefreshTokenRepository repository;

    public RefreshTokenCleanupTransaction(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    /** 활성·폐기 여부와 무관하게 원래 만료시각을 지난 토큰만 정리한다. (SPEC §6.5, 결정-57) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanup(Instant cutoff) {
        return repository.deleteExpired(cutoff);
    }
}

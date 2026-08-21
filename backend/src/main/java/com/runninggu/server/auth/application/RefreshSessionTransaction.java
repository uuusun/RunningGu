package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.RefreshToken;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RefreshSessionTransaction {

    private final RefreshTokenRepository repository;
    private final RefreshTokenHasher tokenHasher;
    private final TokenIssuer tokenIssuer;
    private final Clock clock;

    public RefreshSessionTransaction(
            RefreshTokenRepository repository,
            RefreshTokenHasher tokenHasher,
            TokenIssuer tokenIssuer,
            Clock clock) {
        this.repository = repository;
        this.tokenHasher = tokenHasher;
        this.tokenIssuer = tokenIssuer;
        this.clock = clock;
    }

    /** 재사용 탐지로 revoke한 상태가 오류 응답 때문에 rollback되지 않도록 결과만 반환한다. */
    @Transactional
    public Optional<TokenPair> rotate(String rawToken) {
        RefreshToken current = repository.findByTokenHash(tokenHasher.hash(rawToken))
                .orElse(null);
        if (current == null) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        if (!current.isActiveAt(now)) {
            revokeFamily(current, now);
            return Optional.empty();
        }

        DecodedRefreshToken decoded;
        try {
            decoded = tokenIssuer.decodeRefresh(rawToken);
        } catch (JwtException | IllegalArgumentException exception) {
            revokeFamily(current, now);
            return Optional.empty();
        }
        if (decoded.userId() != current.getUser().getId()) {
            revokeFamily(current, now);
            return Optional.empty();
        }

        current.revoke(now);
        repository.flush();
        IssuedTokenPair issued = tokenIssuer.issue(
                current.getUser().getId(),
                current.getFamilyId(),
                now);
        repository.saveAndFlush(RefreshToken.issue(
                current.getUser(),
                current.getFamilyId(),
                tokenHasher.hash(issued.refreshToken()),
                issued.refreshExpiresAt(),
                now));
        return Optional.of(new TokenPair(issued.accessToken(), issued.refreshToken()));
    }

    @Transactional
    public void logout(String rawToken) {
        repository.findByTokenHash(tokenHasher.hash(rawToken))
                .ifPresent(token -> revokeFamily(token, clock.instant()));
    }

    private void revokeFamily(RefreshToken token, Instant now) {
        repository.findAllByFamilyIdAndRevokedAtIsNull(token.getFamilyId())
                .forEach(active -> active.revoke(now));
    }
}

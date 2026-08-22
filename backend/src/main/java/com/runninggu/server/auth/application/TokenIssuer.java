package com.runninggu.server.auth.application;

import java.time.Instant;
import java.util.UUID;

public interface TokenIssuer {
    IssuedTokenPair issue(long userId, UUID familyId, Instant issuedAt);

    DecodedRefreshToken decodeRefresh(String rawToken);
}

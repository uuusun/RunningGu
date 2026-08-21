package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.DecodedRefreshToken;
import com.runninggu.server.auth.application.IssuedTokenPair;
import com.runninggu.server.auth.application.TokenIssuer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService implements TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final JwtProperties properties;

    public JwtTokenService(
            JwtEncoder encoder,
            @Qualifier("refreshJwtDecoder") JwtDecoder refreshDecoder,
            JwtProperties properties) {
        this.encoder = encoder;
        this.refreshDecoder = refreshDecoder;
        this.properties = properties;
    }

    @Override
    public IssuedTokenPair issue(long userId, UUID familyId, Instant issuedAt) {
        Instant accessExpiresAt = issuedAt.plus(properties.accessTtl());
        Instant refreshExpiresAt = issuedAt.plus(properties.refreshTtl());
        return new IssuedTokenPair(
                familyId,
                encode(userId, "ACCESS", issuedAt, accessExpiresAt),
                encode(userId, "REFRESH", issuedAt, refreshExpiresAt),
                refreshExpiresAt);
    }

    @Override
    public DecodedRefreshToken decodeRefresh(String rawToken) {
        Jwt jwt = refreshDecoder.decode(rawToken);
        return new DecodedRefreshToken(
                Long.parseLong(jwt.getSubject()),
                jwt.getId(),
                jwt.getIssuedAt(),
                jwt.getExpiresAt());
    }

    private String encode(
            long userId,
            String type,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(userId))
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("type", type)
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }
}

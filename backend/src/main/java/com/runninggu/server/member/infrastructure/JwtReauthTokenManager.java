package com.runninggu.server.member.infrastructure;

import com.runninggu.server.auth.infrastructure.JwtProperties;
import com.runninggu.server.member.application.IssuedReauthToken;
import com.runninggu.server.member.application.ReauthTokenManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

/** 탈퇴 목적에만 유효한 5분 JWT를 발급·검증한다. (SPEC 결정-34, API 명세 §2-2) */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtReauthTokenManager implements ReauthTokenManager {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;

    public JwtReauthTokenManager(
            JwtEncoder encoder,
            @Qualifier("reauthJwtDecoder") JwtDecoder decoder,
            JwtProperties properties) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
    }

    @Override
    public IssuedReauthToken issue(long userId, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(TTL);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(userId))
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("type", "REAUTH")
                .claim("purpose", "DELETE_ACCOUNT")
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(headers, claims))
                .getTokenValue();
        return new IssuedReauthToken(token, TTL.toSeconds());
    }

    @Override
    public long decodeUserId(String rawToken) {
        Jwt jwt = decoder.decode(rawToken);
        return Long.parseLong(jwt.getSubject());
    }
}

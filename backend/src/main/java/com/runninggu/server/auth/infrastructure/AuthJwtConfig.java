package com.runninggu.server.auth.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({JwtProperties.class, AgreementProperties.class})
public class AuthJwtConfig {

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        if (!StringUtils.hasText(properties.secret())) {
            throw new IllegalStateException("JWT_SECRET이 필요합니다.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET은 올바른 Base64여야 합니다.", exception);
        }
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET은 디코딩 결과가 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(secretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean("accessJwtDecoder")
    JwtDecoder accessJwtDecoder(
            SecretKey secretKey,
            JwtProperties properties,
            Clock clock) {
        return decoder(secretKey, properties, clock, "ACCESS");
    }

    @Bean("refreshJwtDecoder")
    JwtDecoder refreshJwtDecoder(
            SecretKey secretKey,
            JwtProperties properties,
            Clock clock) {
        return decoder(secretKey, properties, clock, "REFRESH");
    }

    private JwtDecoder decoder(
            SecretKey secretKey,
            JwtProperties properties,
            Clock clock,
            String tokenType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        JwtClaimValidator<List<String>> audience = new JwtClaimValidator<>(
                "aud",
                values -> values != null && values.contains(properties.audience()));
        JwtClaimValidator<String> type = new JwtClaimValidator<>(
                "type",
                tokenType::equals);
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean valid = StringUtils.hasText(jwt.getSubject())
                    && StringUtils.hasText(jwt.getId())
                    && jwt.getIssuedAt() != null
                    && jwt.getExpiresAt() != null;
            if (valid) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "필수 JWT claim이 없습니다.",
                    null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                audience,
                type,
                requiredClaims));
        return decoder;
    }
}

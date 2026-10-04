package com.handoff.auth;

import com.handoff.common.UnauthenticatedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Service for issuing and validating JWT access tokens.
 */
@Service
public class JwtService {

    private final String rawSecret;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;
    private final long wsTicketExpirationSeconds;
    private final Environment environment;

    private SecretKey key;

    public JwtService(
            @Value("${handoff.jwt.secret:}") String rawSecret,
            @Value("${handoff.jwt.access-token-expiration-seconds:900}") long accessTokenExpirationSeconds,
            @Value("${handoff.jwt.refresh-token-expiration-seconds:604800}") long refreshTokenExpirationSeconds,
            @Value("${handoff.jwt.ws-ticket-expiration-seconds:30}") long wsTicketExpirationSeconds,
            Environment environment
    ) {
        this.rawSecret = rawSecret;
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.wsTicketExpirationSeconds = wsTicketExpirationSeconds;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        boolean isDev = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(profile)) {
                isDev = true;
                break;
            }
        }

        String secret = rawSecret;
        if (secret == null || secret.isBlank()) {
            if (isDev) {
                secret = "dev-secret-key-must-be-at-least-32-bytes-long-for-hs256!";
            } else {
                throw new IllegalStateException("JWT secret is not configured. Outside dev, a secret of at least 32 bytes is required.");
            }
        }

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes (256 bits) for HMAC-SHA256, but found " + secretBytes.length + " bytes.");
        }

        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateAccessToken(UUID userId, UUID organizationId, Role role, String displayName) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpirationSeconds);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("orgId", organizationId.toString())
                .claim("role", role.name())
                .claim("name", displayName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Claims parseAndValidate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthenticatedException("Invalid or expired access token");
        }
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationSeconds;
    }

    public long getWsTicketExpirationSeconds() {
        return wsTicketExpirationSeconds;
    }
}

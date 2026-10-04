package com.handoff.auth;

import com.handoff.common.UnauthenticatedException;
import com.handoff.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication service orchestrating registration, login, refresh rotation, and logout.
 */
@Service
public class AuthService {

    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimiterService rateLimiterService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            AuthRepository authRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RateLimiterService rateLimiterService
    ) {
        this.authRepository = authRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiterService = rateLimiterService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String normalizedEmail = req.email().trim().toLowerCase();

        if (authRepository.findUserByEmail(normalizedEmail).isPresent()) {
            throw new ValidationException(
                    "Registration failed",
                    Map.of("email", "Email is already registered")
            );
        }

        UUID orgId = UUID.randomUUID();
        authRepository.createOrganization(orgId, req.organizationName().trim());

        UUID userId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(req.password());
        authRepository.createUser(userId, normalizedEmail, passwordHash, req.displayName().trim());

        authRepository.createMembership(userId, orgId, Role.ADMIN);

        return createAuthResponse(userId, orgId, Role.ADMIN, req.displayName().trim());
    }

    public AuthResponse login(LoginRequest req, String clientIp) {
        rateLimiterService.checkLoginRateLimit(clientIp);

        String normalizedEmail = req.email().trim().toLowerCase();
        var userOpt = authRepository.findUserByEmail(normalizedEmail);

        if (userOpt.isEmpty() || !passwordEncoder.matches(req.password(), userOpt.get().passwordHash())) {
            throw new UnauthenticatedException("Invalid email or password");
        }

        var user = userOpt.get();
        var membershipOpt = authRepository.findMembershipByUserId(user.id());
        if (membershipOpt.isEmpty()) {
            throw new UnauthenticatedException("User has no organization membership");
        }

        var membership = membershipOpt.get();
        return createAuthResponse(user.id(), membership.organizationId(), membership.role(), user.displayName());
    }

    @Transactional(noRollbackFor = UnauthenticatedException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new UnauthenticatedException("Refresh token is required");
        }

        String tokenHash = hashToken(rawRefreshToken.trim());
        var tokenOpt = authRepository.findRefreshToken(tokenHash);

        if (tokenOpt.isEmpty()) {
            throw new UnauthenticatedException("Invalid refresh token");
        }

        var token = tokenOpt.get();

        // Reuse detection: if an already-revoked refresh token is presented, revoke all tokens for this user
        if (token.revoked()) {
            authRepository.revokeAllUserRefreshTokens(token.userId());
            throw new UnauthenticatedException("Invalid refresh token");
        }

        if (token.expiresAt().isBefore(Instant.now())) {
            throw new UnauthenticatedException("Refresh token has expired");
        }

        // Conditional update ensures atomic rotation under concurrency:
        // exactly one thread can rotate this token.
        int updated = authRepository.rotateRefreshToken(tokenHash);
        if (updated != 1) {
            throw new UnauthenticatedException("Invalid or already rotated refresh token");
        }

        var user = authRepository.findUserById(token.userId())
                .orElseThrow(() -> new UnauthenticatedException("User not found"));
        var membership = authRepository.findMembershipByUserId(token.userId())
                .orElseThrow(() -> new UnauthenticatedException("Membership not found"));

        return createAuthResponse(user.id(), membership.organizationId(), membership.role(), user.displayName());
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = hashToken(rawRefreshToken.trim());
            authRepository.revokeRefreshToken(tokenHash);
        }
    }

    private AuthResponse createAuthResponse(UUID userId, UUID organizationId, Role role, String displayName) {
        String accessToken = jwtService.generateAccessToken(userId, organizationId, role, displayName);
        String rawRefreshToken = generateRawToken();
        String tokenHash = hashToken(rawRefreshToken);

        Instant expiresAt = Instant.now().plusSeconds(jwtService.getRefreshTokenExpirationSeconds());
        authRepository.insertRefreshToken(UUID.randomUUID(), userId, tokenHash, expiresAt);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtService.getAccessTokenExpirationSeconds(),
                new AuthResponse.UserDto(userId, displayName, role, organizationId)
        );
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm not available", e);
        }
    }
}

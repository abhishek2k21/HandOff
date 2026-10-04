package com.handoff.auth;

import java.util.UUID;

/**
 * Authentication response conforming to docs/events.md section 14.2.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserDto user
) {
    public record UserDto(
            UUID id,
            String name,
            Role role,
            UUID organizationId
    ) {}
}

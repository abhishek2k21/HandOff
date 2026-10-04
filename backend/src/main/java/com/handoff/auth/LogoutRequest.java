package com.handoff.auth;

/**
 * Request body for POST /api/auth/logout.
 */
public record LogoutRequest(
        String refreshToken
) {}

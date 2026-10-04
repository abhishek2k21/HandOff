package com.handoff.auth;

/**
 * Response for POST /api/ws-ticket conforming to docs/events.md section 14.2.
 */
public record WsTicketResponse(
        String ticket,
        long expiresInSeconds
) {}

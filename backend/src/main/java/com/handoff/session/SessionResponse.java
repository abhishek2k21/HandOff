package com.handoff.session;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
    UUID id,
    String ticketId,
    String agentType,
    String scenario,
    String status,
    UUID controllerUserId,
    long lastSeq,
    int stepBudget,
    int tokenBudget,
    Instant createdAt,
    Instant endedAt
) {
    public static SessionResponse from(Session s) {
        return new SessionResponse(
            s.id(),
            s.ticketId(),
            s.agentType(),
            s.scenario(),
            s.status().name(),
            s.controllerUserId(),
            s.lastSeq(),
            s.stepBudget(),
            s.tokenBudget(),
            s.createdAt(),
            s.endedAt()
        );
    }
}

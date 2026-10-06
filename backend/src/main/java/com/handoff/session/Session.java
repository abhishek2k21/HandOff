package com.handoff.session;

import java.time.Instant;
import java.util.UUID;

public record Session(
    UUID id,
    UUID organizationId,
    String ticketId,
    String agentType,
    String scenario,
    SessionStatus status,
    UUID controllerUserId,
    long lastSeq,
    int stepBudget,
    int tokenBudget,
    int stepsUsed,
    int tokensUsed,
    UUID createdBy,
    Instant createdAt,
    Instant endedAt
) {}

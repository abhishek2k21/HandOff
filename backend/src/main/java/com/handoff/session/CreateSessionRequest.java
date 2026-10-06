package com.handoff.session;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
    @NotBlank(message = "ticketId is required")
    String ticketId,
    String agentType,
    String scenario,
    Integer stepBudget,
    Integer tokenBudget
) {}

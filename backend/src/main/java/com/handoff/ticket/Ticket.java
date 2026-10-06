package com.handoff.ticket;

import java.time.Instant;
import java.util.UUID;

public record Ticket(
    UUID organizationId,
    String id,
    String customerName,
    String customerEmail,
    String subject,
    String description,
    String status,
    String orderId,
    Instant createdAt,
    Instant updatedAt
) {}

package com.handoff.order;

import java.time.Instant;
import java.util.UUID;

public record Order(
    UUID organizationId,
    String id,
    String customerName,
    String customerEmail,
    String itemsJson,
    int totalAmount,
    String currency,
    String status,
    Instant createdAt
) {}

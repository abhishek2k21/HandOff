package com.handoff.ticket;

import java.time.Instant;

public record TicketResponse(
    String id,
    String customerName,
    String customerEmail,
    String subject,
    String description,
    String status,
    String orderId,
    Instant createdAt,
    Instant updatedAt
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(
            t.id(),
            t.customerName(),
            t.customerEmail(),
            t.subject(),
            t.description(),
            t.status(),
            t.orderId(),
            t.createdAt(),
            t.updatedAt()
        );
    }
}

package com.handoff.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Event(
    UUID sessionId,
    long seq,
    String type,
    Actor actor,
    String commandId,
    Map<String, Object> payload,
    Instant createdAt
) {}

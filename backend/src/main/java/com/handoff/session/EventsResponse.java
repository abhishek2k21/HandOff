package com.handoff.session;

import com.handoff.events.Event;
import java.util.List;
import java.util.UUID;

public record EventsResponse(
    UUID sessionId,
    List<Event> events,
    long lastSeq,
    boolean hasMore
) {}

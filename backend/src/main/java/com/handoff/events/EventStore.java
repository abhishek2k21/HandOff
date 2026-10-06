package com.handoff.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventStore {
    Event append(
            UUID sessionId,
            String type,
            ActorKind actorKind,
            String actorId,
            String actorName,
            String commandId,
            Map<String, Object> payload
    );

    List<Event> getEvents(UUID sessionId, long fromSeq, int limit);
}

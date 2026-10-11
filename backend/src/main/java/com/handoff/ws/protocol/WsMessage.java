package com.handoff.ws.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.handoff.events.Event;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Protocol message records matching docs/events.md section 9.
 */
public final class WsMessage {

    private WsMessage() {}

    // --- Client-to-Server Messages ---

    public record AuthRequest(String op, String ticket) {}

    public record SubscribeRequest(String op, UUID sessionId, Long fromSeq) {}

    public record UnsubscribeRequest(String op, UUID sessionId) {}

    public record PongMessage(String op) {}

    public record CommandRequest(String op, String id, UUID sessionId, String type, Map<String, Object> payload) {}

    // --- Server-to-Client Messages ---

    public record AuthOkUser(UUID id, String role, String name) {}

    public record AuthOkResponse(String op, int protocol, AuthOkUser user) {
        public AuthOkResponse(AuthOkUser user) {
            this("auth_ok", 1, user);
        }
    }

    public record SubscribedResponse(String op, UUID sessionId, long lastSeq) {
        public SubscribedResponse(UUID sessionId, long lastSeq) {
            this("subscribed", sessionId, lastSeq);
        }
    }

    public record EventsBatchResponse(String op, UUID sessionId, List<Event> events) {
        public EventsBatchResponse(UUID sessionId, List<Event> events) {
            this("events", sessionId, events);
        }
    }

    public record LiveEventResponse(String op, Event event) {
        public LiveEventResponse(Event event) {
            this("event", event);
        }
    }

    public record CaughtUpResponse(String op, UUID sessionId, long lastSeq) {
        public CaughtUpResponse(UUID sessionId, long lastSeq) {
            this("caught_up", sessionId, lastSeq);
        }
    }

    public record PingMessage(String op) {
        public PingMessage() {
            this("ping");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String op,
            String id,
            String code,
            String message,
            Map<String, Object> details
    ) {
        public ErrorResponse(String id, String code, String message, Map<String, Object> details) {
            this("error", id, code, message, details != null ? details : Map.of());
        }

        public ErrorResponse(String code, String message) {
            this("error", null, code, message, Map.of());
        }
    }
}

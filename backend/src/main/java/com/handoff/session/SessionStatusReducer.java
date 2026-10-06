package com.handoff.session;

import org.springframework.stereotype.Component;

/**
 * Pure state reducer deriving session status from the event stream
 * per docs/events.md section 7.2.
 */
@Component
public class SessionStatusReducer {

    public SessionStatus reduce(SessionStatus current, String eventType) {
        return switch (eventType) {
            case "SESSION_STARTED", "RESUMED", "APPROVAL_DECIDED" -> SessionStatus.RUNNING;
            case "PAUSED" -> SessionStatus.PAUSED;
            case "APPROVAL_REQUESTED" -> SessionStatus.AWAITING_APPROVAL;
            case "HANDOFF_SUMMARY" -> SessionStatus.HANDED_OFF;
            case "SESSION_COMPLETED" -> SessionStatus.COMPLETED;
            case "SESSION_FAILED" -> SessionStatus.FAILED;
            default -> current;
        };
    }
}

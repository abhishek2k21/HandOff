package com.handoff.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SessionStatusReducerTest {

    private SessionStatusReducer reducer;

    @BeforeEach
    void setUp() {
        reducer = new SessionStatusReducer();
    }

    @ParameterizedTest
    @CsvSource({
        "RUNNING, SESSION_STARTED, RUNNING",
        "PAUSED, SESSION_STARTED, RUNNING",
        "RUNNING, PAUSED, PAUSED",
        "PAUSED, RESUMED, RUNNING",
        "HANDED_OFF, RESUMED, RUNNING",
        "RUNNING, APPROVAL_REQUESTED, AWAITING_APPROVAL",
        "AWAITING_APPROVAL, APPROVAL_DECIDED, RUNNING",
        "RUNNING, HANDOFF_SUMMARY, HANDED_OFF",
        "PAUSED, HANDOFF_SUMMARY, HANDED_OFF",
        "RUNNING, SESSION_COMPLETED, COMPLETED",
        "RUNNING, SESSION_FAILED, FAILED",
        "PAUSED, SESSION_FAILED, FAILED",
        "AWAITING_APPROVAL, SESSION_FAILED, FAILED"
    })
    void testStateTransitions(SessionStatus current, String eventType, SessionStatus expected) {
        assertEquals(expected, reducer.reduce(current, eventType));
    }

    @Test
    void nonStateChangingEventsLeaveStatusUnchanged() {
        String[] events = {
            "AGENT_TEXT", "TOOL_CALL", "TOOL_RESULT", "STEER",
            "HUMAN_MESSAGE", "CONTROL_TAKEN", "CONTROL_RELEASED",
            "BUDGET_EXCEEDED", "AGENT_RECOVERED", "ERROR"
        };

        for (SessionStatus status : SessionStatus.values()) {
            for (String eventType : events) {
                assertEquals(status, reducer.reduce(status, eventType),
                        "Event " + eventType + " should not change status " + status);
            }
        }
    }
}

package com.handoff.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handoff.AbstractIntegrationTest;
import com.handoff.events.Event;
import com.handoff.events.EventStore;
import com.handoff.order.Order;
import com.handoff.order.OrderRepository;
import com.handoff.session.CreateSessionRequest;
import com.handoff.session.Session;
import com.handoff.session.SessionRepository;
import com.handoff.session.SessionResponse;
import com.handoff.session.SessionService;
import com.handoff.session.SessionStatus;
import com.handoff.ticket.Ticket;
import com.handoff.ticket.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ScriptedAgentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private AgentRunner agentRunner;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private ToolRuntime toolRuntime;

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void simpleLookupExecutesExactThirteenEventSequence() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);

        // Seed order 8841 and ticket T-101
        orderRepository.createOrder(new Order(
                orgId, "8841", "Aarav Sharma", "aarav@example.com",
                "[{\"sku\":\"SKU-99\",\"name\":\"Headphones\",\"qty\":1,\"price\":4200}]",
                4200, "INR", "DELIVERED", Instant.now()
        ));
        ticketRepository.createTicket(new Ticket(
                orgId, "T-101", "Aarav Sharma", "aarav@example.com",
                "Where is my order?", "Need update", "OPEN", "8841",
                Instant.now(), Instant.now()
        ));

        SessionResponse sessionResp = sessionService.createSession(
                orgId, userId, "Maya Operator",
                new CreateSessionRequest("T-101", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000)
        );
        UUID sessionId = sessionResp.id();

        // Await agent completion (deterministic with zero delay)
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            Session s = sessionRepository.findById(orgId, sessionId).orElseThrow();
            return s.status() == SessionStatus.COMPLETED;
        });

        List<Event> events = eventStore.getEvents(sessionId, 0, 100);
        assertEquals(13, events.size(), "SIMPLE_LOOKUP must produce exactly 13 events");

        // Verify event types in order (seq 1..13)
        assertEquals("SESSION_STARTED", events.get(0).type());
        assertEquals(1L, events.get(0).seq());

        assertEquals("CONTROL_TAKEN", events.get(1).type());
        assertEquals(2L, events.get(1).seq());

        assertEquals("AGENT_TEXT", events.get(2).type());
        assertEquals("Looking up order 8841...", events.get(2).payload().get("text"));

        assertEquals("TOOL_CALL", events.get(3).type());
        assertEquals("lookup_order", events.get(3).payload().get("tool"));
        assertEquals("tc-1", events.get(3).payload().get("toolCallId"));

        assertEquals("TOOL_RESULT", events.get(4).type());
        assertEquals("tc-1", events.get(4).payload().get("toolCallId"));
        assertTrue((Boolean) events.get(4).payload().get("ok"));

        assertEquals("AGENT_TEXT", events.get(5).type());
        assertTrue(((String) events.get(5).payload().get("text")).contains("Order 8841 is delivered"));

        assertEquals("TOOL_CALL", events.get(6).type());
        assertEquals("add_note", events.get(6).payload().get("tool"));
        assertEquals("tc-2", events.get(6).payload().get("toolCallId"));

        assertEquals("TOOL_RESULT", events.get(7).type());
        assertEquals("tc-2", events.get(7).payload().get("toolCallId"));

        assertEquals("AGENT_TEXT", events.get(8).type());
        assertTrue(((String) events.get(8).payload().get("text")).contains("Closing ticket T-101..."));

        assertEquals("TOOL_CALL", events.get(9).type());
        assertEquals("close_ticket", events.get(9).payload().get("tool"));
        assertEquals("tc-3", events.get(9).payload().get("toolCallId"));

        assertEquals("TOOL_RESULT", events.get(10).type());
        assertEquals("tc-3", events.get(10).payload().get("toolCallId"));

        assertEquals("AGENT_TEXT", events.get(11).type());
        assertEquals("Ticket resolved and closed.", events.get(11).payload().get("text"));

        assertEquals("SESSION_COMPLETED", events.get(12).type());
        assertEquals(13L, events.get(12).seq());
        assertEquals("RESOLVED", events.get(12).payload().get("outcome"));

        // Verify ticket status is now CLOSED and note exists
        Ticket updatedTicket = ticketRepository.findById(orgId, "T-101").orElseThrow();
        assertEquals("CLOSED", updatedTicket.status());

        List<Map<String, Object>> notes = ticketRepository.getNotes(orgId, "T-101");
        assertFalse(notes.isEmpty());
        assertTrue(((String) notes.get(0).get("text")).contains("Verified order 8841"));
    }

    @Test
    void simpleLookupWithNoLinkedOrderFailsWithToolFailedAndAgentError() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);

        // Ticket with NO linked order
        ticketRepository.createTicket(new Ticket(
                orgId, "T-NO-ORDER", "Customer", "cust@example.com",
                "Question", "General info", "OPEN", null,
                Instant.now(), Instant.now()
        ));

        SessionResponse sessionResp = sessionService.createSession(
                orgId, userId, "Maya Operator",
                new CreateSessionRequest("T-NO-ORDER", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000)
        );
        UUID sessionId = sessionResp.id();

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            Session s = sessionRepository.findById(orgId, sessionId).orElseThrow();
            return s.status() == SessionStatus.FAILED;
        });

        List<Event> events = eventStore.getEvents(sessionId, 0, 100);
        assertEquals(4, events.size()); // seq 1, 2, ERROR, SESSION_FAILED

        Event errorEvent = events.get(2);
        assertEquals("ERROR", errorEvent.type());
        assertEquals("TOOL_FAILED", errorEvent.payload().get("code"));

        Event failedEvent = events.get(3);
        assertEquals("SESSION_FAILED", failedEvent.type());
        assertEquals("AGENT_ERROR", failedEvent.payload().get("reason"));
    }

    @Test
    void longStreamEmitsTwoThousandAndThreeEventsAndDoesNotCloseTicket() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);

        ticketRepository.createTicket(new Ticket(
                orgId, "T-LONG", "Tester", "test@example.com",
                "Benchmark", "Streaming", "OPEN", null,
                Instant.now(), Instant.now()
        ));

        SessionResponse sessionResp = sessionService.createSession(
                orgId, userId, "Maya Operator",
                new CreateSessionRequest("T-LONG", "SCRIPTED", "LONG_STREAM", 20, 20000)
        );
        UUID sessionId = sessionResp.id();

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            Session s = sessionRepository.findById(orgId, sessionId).orElseThrow();
            return s.status() == SessionStatus.COMPLETED;
        });

        Session finalSession = sessionRepository.findById(orgId, sessionId).orElseThrow();
        assertEquals(2003L, finalSession.lastSeq());

        // Ticket should remain OPEN (LONG_STREAM does not close it)
        Ticket ticket = ticketRepository.findById(orgId, "T-LONG").orElseThrow();
        assertEquals("OPEN", ticket.status());
    }

    @Test
    void toolAllowlistRejection() {
        UUID orgId = createTestOrg();
        assertFalse(toolRuntime.isAllowed("rm_rf"));
        assertFalse(toolRuntime.isAllowed("drop_database"));
        assertTrue(toolRuntime.isAllowed("lookup_order"));
        assertTrue(toolRuntime.isAllowed("add_note"));
        assertTrue(toolRuntime.isAllowed("close_ticket"));
        assertTrue(toolRuntime.isAllowed("issue_refund"));

        assertThrows(IllegalArgumentException.class, () ->
                toolRuntime.execute(orgId, "unauthorized_tool", Map.of())
        );
    }

    @Test
    void requirement6_toolDbEffectAndToolResultCommitInOneTransaction() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        String ticketId = "T-ATOM";
        ticketRepository.createTicket(new Ticket(
                orgId, ticketId, "Customer", "c@example.com",
                "Atomicity test", "Desc", "OPEN", null,
                Instant.now(), Instant.now()
        ));

        // Create a session manually
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by, ended_at)
            VALUES (?, ?, ?, 'SCRIPTED', 'RUNNING', 0, 20, 20000, ?, now())
            """, sessionId, orgId, ticketId, userId);
        // Note: we intentionally set ended_at = now() so any eventStore.append will fail with SESSION_ENDED!

        // When toolExecutionService.executeAndRecord runs, tool effect executes but appending TOOL_RESULT fails!
        assertThrows(Exception.class, () ->
                toolExecutionService.executeAndRecord(
                        orgId,
                        sessionId,
                        "tc-atom",
                        "add_note",
                        Map.of("ticketId", ticketId, "text", "This note should not be saved")
                )
        );

        // Verify the note was rolled back and is NOT saved!
        List<Map<String, Object>> notes = ticketRepository.getNotes(orgId, ticketId);
        assertTrue(notes.isEmpty(), "Note must NOT be saved when TOOL_RESULT append fails");
    }

    @Test
    void requirement5_unexpectedAgentExceptionAppendsInternalErrorAndSessionFailed() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        String ticketId = "T-FAIL";

        // Seed valid order and ticket
        orderRepository.createOrder(new Order(
                orgId, "8841-FAIL", "Customer", "c@example.com", "[]",
                100, "INR", "DELIVERED", Instant.now()
        ));
        ticketRepository.createTicket(new Ticket(
                orgId, ticketId, "Customer", "c@example.com",
                "Fail test", "Desc", "OPEN", "8841-FAIL",
                Instant.now(), Instant.now()
        ));

        // Force a tool failure via Mockito spy on toolRuntime
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated tool crash"))
                .when(toolRuntime).execute(
                        org.mockito.ArgumentMatchers.eq(orgId),
                        org.mockito.ArgumentMatchers.eq("lookup_order"),
                        org.mockito.ArgumentMatchers.any()
                );

        // Create a session with scenario SIMPLE_LOOKUP
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, scenario, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, ?, 'SCRIPTED', 'SIMPLE_LOOKUP', 'RUNNING', 0, 20, 20000, ?)
            """, sessionId, orgId, ticketId, userId);

        // Run agent directly to trigger the forced tool failure
        agentRunner.runSession(sessionId);

        // Session must transition to FAILED, not left RUNNING
        Session session = sessionRepository.findById(orgId, sessionId).orElseThrow();
        assertEquals(SessionStatus.FAILED, session.status());
        assertNotNull(session.endedAt());

        List<Event> events = eventStore.getEvents(sessionId, 0, 100);
        Event errorEvent = events.stream().filter(e -> "ERROR".equals(e.type())).findFirst().orElseThrow();
        assertEquals("INTERNAL", errorEvent.payload().get("code"));
        assertEquals(false, errorEvent.payload().get("recoverable"));

        Event failedEvent = events.stream().filter(e -> "SESSION_FAILED".equals(e.type())).findFirst().orElseThrow();
        assertEquals("INTERNAL", failedEvent.payload().get("reason"));
    }

    @Test
    void tenantIsolationPreventsAccessToOtherOrganizationOrders() {
        UUID orgA = createTestOrg();
        UUID orgB = createTestOrg();

        orderRepository.createOrder(new Order(
                orgB, "9999", "Foreign", "f@example.com", "[]",
                100, "INR", "DELIVERED", Instant.now()
        ));

        // Attempting to execute lookup_order for Org B's order within Org A's scope must fail
        assertThrows(IllegalArgumentException.class, () ->
                toolRuntime.execute(orgA, "lookup_order", Map.of("orderId", "9999"))
        );
    }

    private UUID createTestOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Org " + orgId);
        return orgId;
    }

    private UUID createTestUser(UUID orgId) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, 'hash', 'Test User')",
                userId, "user-" + userId + "@example.com"
        );
        jdbc.update(
                "INSERT INTO memberships (user_id, organization_id, role) VALUES (?, ?, 'OPERATOR')",
                userId, orgId
        );
        return userId;
    }
}

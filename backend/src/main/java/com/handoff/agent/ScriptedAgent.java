package com.handoff.agent;

import com.handoff.events.ActorKind;
import com.handoff.events.EventStore;
import com.handoff.session.Session;
import com.handoff.ticket.Ticket;
import com.handoff.ticket.TicketRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic scripted agent executing predefined scenarios per docs/events.md section 13.4.
 * Uses ticketId and linked orderId dynamically from the session's ticket.
 */
@Component
public class ScriptedAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ScriptedAgent.class);

    private final EventStore eventStore;
    private final TicketRepository ticketRepository;
    private final ToolExecutionService toolExecutionService;
    private final long stepDelayMs;

    public ScriptedAgent(
            EventStore eventStore,
            TicketRepository ticketRepository,
            ToolExecutionService toolExecutionService,
            @Value("${handoff.agent.step-delay-ms:0}") long stepDelayMs
    ) {
        this.eventStore = eventStore;
        this.ticketRepository = ticketRepository;
        this.toolExecutionService = toolExecutionService;
        this.stepDelayMs = stepDelayMs;
    }

    @Override
    public void execute(Session session) {
        String scenario = session.scenario();
        if (scenario == null) {
            scenario = "SIMPLE_LOOKUP";
        }

        switch (scenario) {
            case "SIMPLE_LOOKUP" -> executeSimpleLookup(session);
            case "LONG_STREAM" -> executeLongStream(session);
            default -> {
                log.warn("Unknown scenario: {}", scenario);
                eventStore.append(session.id(), "ERROR", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                        "code", "TOOL_FAILED",
                        "message", "Unsupported scenario: " + scenario,
                        "recoverable", false
                ));
                eventStore.append(session.id(), "SESSION_FAILED", ActorKind.SYSTEM, "system", "System", null, Map.of(
                        "reason", "AGENT_ERROR",
                        "message", "Scenario not supported: " + scenario
                ));
            }
        }
    }

    private void executeSimpleLookup(Session session) {
        UUID sessionId = session.id();
        UUID orgId = session.organizationId();
        String ticketId = session.ticketId();

        Ticket ticket = ticketRepository.findById(orgId, ticketId).orElse(null);
        if (ticket == null || ticket.orderId() == null || ticket.orderId().isBlank()) {
            log.info("Ticket {} has no linked order. Appending ERROR and SESSION_FAILED.", ticketId);
            pauseIfNeeded();
            eventStore.append(sessionId, "ERROR", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                    "code", "TOOL_FAILED",
                    "message", "No linked order for ticket " + ticketId,
                    "recoverable", false
            ));
            pauseIfNeeded();
            eventStore.append(sessionId, "SESSION_FAILED", ActorKind.SYSTEM, "system", "System", null, Map.of(
                    "reason", "AGENT_ERROR",
                    "message", "Failed to perform lookup: ticket has no linked order."
            ));
            return;
        }

        String orderId = ticket.orderId();

        // 1. AGENT_TEXT: Looking up order
        pauseIfNeeded();
        eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                "messageId", "m-1",
                "text", "Looking up order " + orderId + "...",
                "final", true
        ));

        // 2. TOOL_CALL: lookup_order (appended BEFORE execution per Requirement 6)
        pauseIfNeeded();
        eventStore.append(sessionId, "TOOL_CALL", ActorKind.AGENT, "agent", "Agent", null, createToolCallPayload(
                "tc-1", "lookup_order", Map.of("orderId", orderId), false, null
        ));

        // 3. Execute tool and record TOOL_RESULT in one transaction
        pauseIfNeeded();
        toolExecutionService.executeAndRecord(orgId, sessionId, "tc-1", "lookup_order", Map.of("orderId", orderId));

        // 4. AGENT_TEXT: Order is delivered
        pauseIfNeeded();
        eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                "messageId", "m-2",
                "text", "Order " + orderId + " is delivered. Adding note to ticket...",
                "final", true
        ));

        // 5. TOOL_CALL: add_note
        pauseIfNeeded();
        eventStore.append(sessionId, "TOOL_CALL", ActorKind.AGENT, "agent", "Agent", null, createToolCallPayload(
                "tc-2", "add_note", Map.of("ticketId", ticketId, "text", "Verified order " + orderId + " status: DELIVERED."), false, null
        ));

        // 6. Execute add_note and record TOOL_RESULT in one transaction
        pauseIfNeeded();
        toolExecutionService.executeAndRecord(orgId, sessionId, "tc-2", "add_note", Map.of(
                "ticketId", ticketId,
                "text", "Verified order " + orderId + " status: DELIVERED."
        ));

        // 7. AGENT_TEXT: Closing ticket
        pauseIfNeeded();
        eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                "messageId", "m-3",
                "text", "Closing ticket " + ticketId + "...",
                "final", true
        ));

        // 8. TOOL_CALL: close_ticket
        pauseIfNeeded();
        eventStore.append(sessionId, "TOOL_CALL", ActorKind.AGENT, "agent", "Agent", null, createToolCallPayload(
                "tc-3", "close_ticket", Map.of("ticketId", ticketId, "resolution", "Informed customer that order " + orderId + " was delivered."), false, null
        ));

        // 9. Execute close_ticket and record TOOL_RESULT in one transaction
        pauseIfNeeded();
        toolExecutionService.executeAndRecord(orgId, sessionId, "tc-3", "close_ticket", Map.of(
                "ticketId", ticketId,
                "resolution", "Informed customer that order " + orderId + " was delivered."
        ));

        // 10. AGENT_TEXT: Ticket resolved and closed
        pauseIfNeeded();
        eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                "messageId", "m-4",
                "text", "Ticket resolved and closed.",
                "final", true
        ));

        // 11. SESSION_COMPLETED
        pauseIfNeeded();
        eventStore.append(sessionId, "SESSION_COMPLETED", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                "outcome", "RESOLVED",
                "summary", "Looked up order " + orderId + ", added verification note, and closed ticket."
        ));
    }

    private void executeLongStream(Session session) {
        UUID sessionId = session.id();
        String ticketId = session.ticketId();

        for (int i = 1; i <= 2000; i++) {
            pauseIfNeeded();
            boolean isFinal = (i == 2000);
            eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                    "messageId", "m-1",
                    "text", "Chunk " + i + " of 2000 for ticket " + ticketId + "...",
                    "final", isFinal
            ));
        }

        pauseIfNeeded();
        eventStore.append(sessionId, "SESSION_COMPLETED", ActorKind.AGENT, "agent", "Agent", null, Map.of(
                "outcome", "COMPLETED",
                "summary", "Completed 2000 stream chunks."
        ));
    }

    private Map<String, Object> createToolCallPayload(
            String toolCallId,
            String tool,
            Map<String, Object> args,
            boolean risky,
            String riskReason
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("toolCallId", toolCallId);
        payload.put("tool", tool);
        payload.put("args", args);
        payload.put("risky", risky);
        payload.put("riskReason", riskReason);
        return payload;
    }

    private void pauseIfNeeded() {
        if (stepDelayMs > 0) {
            try {
                Thread.sleep(stepDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Scripted agent interrupted", e);
            }
        }
    }
}

package com.handoff.session;

import com.handoff.agent.AgentRunner;
import com.handoff.common.ApiException;
import com.handoff.events.ActorKind;
import com.handoff.events.Event;
import com.handoff.events.EventStore;
import com.handoff.ticket.Ticket;
import com.handoff.ticket.TicketRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final TicketRepository ticketRepository;
    private final EventStore eventStore;
    private final AgentRunner agentRunner;

    public SessionService(
            SessionRepository sessionRepository,
            TicketRepository ticketRepository,
            EventStore eventStore,
            AgentRunner agentRunner
    ) {
        this.sessionRepository = sessionRepository;
        this.ticketRepository = ticketRepository;
        this.eventStore = eventStore;
        this.agentRunner = agentRunner;
    }

    /**
     * Outer non-transactional boundary that wraps transaction execution and maps
     * database unique constraint violations to 409 INVALID_STATE outside the failed transaction (Requirement 4).
     */
    public SessionResponse createSession(UUID orgId, UUID userId, String userDisplayName, CreateSessionRequest req) {
        try {
            return executeCreateSessionTransaction(orgId, userId, userDisplayName, req);
        } catch (DataIntegrityViolationException e) {
            if (isTicketUniqueConstraintViolation(e)) {
                log.info("Caught active session unique index violation for ticket {}", req.ticketId());
                throw new ApiException(
                        "INVALID_STATE",
                        "An active session already exists for this ticket",
                        HttpStatus.CONFLICT,
                        Map.of("fields", Map.of("ticketId", "An active session already exists for this ticket"))
                );
            }
            throw e;
        }
    }

    @Transactional
    public SessionResponse executeCreateSessionTransaction(
            UUID orgId,
            UUID userId,
            String userDisplayName,
            CreateSessionRequest req
    ) {
        // 1. Validate scenario
        String scenario = req.scenario() != null ? req.scenario() : "SIMPLE_LOOKUP";
        if (!"SIMPLE_LOOKUP".equals(scenario) && !"LONG_STREAM".equals(scenario)) {
            throw new ApiException(
                    "VALIDATION_FAILED",
                    "Invalid scenario: " + scenario,
                    HttpStatus.BAD_REQUEST,
                    Map.of("fields", Map.of("scenario", "Scenario must be SIMPLE_LOOKUP or LONG_STREAM"))
            );
        }

        // 2. Validate agentType
        String agentType = req.agentType() != null ? req.agentType() : "SCRIPTED";
        if (!"SCRIPTED".equalsIgnoreCase(agentType)) {
            throw new ApiException(
                    "VALIDATION_FAILED",
                    "Only SCRIPTED agentType is supported",
                    HttpStatus.BAD_REQUEST,
                    Map.of("fields", Map.of("agentType", "Only SCRIPTED agentType is supported in this slice"))
            );
        }

        // 3. Validate ticket exists in user's organization
        Ticket ticket = ticketRepository.findById(orgId, req.ticketId()).orElseThrow(() ->
                new ApiException(
                        "VALIDATION_FAILED",
                        "Ticket not found: " + req.ticketId(),
                        HttpStatus.BAD_REQUEST,
                        Map.of("fields", Map.of("ticketId", "Ticket not found"))
                )
        );

        // 4. Validate ticket is not CLOSED
        if ("CLOSED".equalsIgnoreCase(ticket.status())) {
            throw new ApiException(
                    "VALIDATION_FAILED",
                    "Cannot create session for CLOSED ticket",
                    HttpStatus.BAD_REQUEST,
                    Map.of("fields", Map.of("ticketId", "Cannot create session for CLOSED ticket"))
            );
        }

        // 5. Check first if active session already exists for this ticket
        if (sessionRepository.hasActiveSessionForTicket(orgId, req.ticketId())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "An active session already exists for this ticket",
                    HttpStatus.CONFLICT,
                    Map.of("fields", Map.of("ticketId", "An active session already exists for this ticket"))
            );
        }

        // 6. Create session record
        UUID sessionId = UUID.randomUUID();
        int stepBudget = req.stepBudget() != null ? req.stepBudget() : 20;
        int tokenBudget = req.tokenBudget() != null ? req.tokenBudget() : 20000;
        Instant now = Instant.now();

        Session session = new Session(
                sessionId,
                orgId,
                req.ticketId(),
                agentType,
                scenario,
                SessionStatus.RUNNING,
                userId,
                0, // last_seq starts at 0 before events
                stepBudget,
                tokenBudget,
                0,
                0,
                userId,
                now,
                null
        );
        sessionRepository.createSession(session);

        // 7. Append seq 1: SESSION_STARTED
        Map<String, Object> startedPayload = new HashMap<>();
        startedPayload.put("ticketId", req.ticketId());
        startedPayload.put("agentType", agentType);
        startedPayload.put("scenario", scenario);
        startedPayload.put("stepBudget", stepBudget);
        startedPayload.put("tokenBudget", tokenBudget);
        startedPayload.put("createdBy", userId.toString());

        Event startedEvent = eventStore.append(
                sessionId,
                "SESSION_STARTED",
                ActorKind.USER,
                userId.toString(),
                userDisplayName,
                null,
                startedPayload
        );

        // 8. Append seq 2: CONTROL_TAKEN
        Map<String, Object> controlPayload = new HashMap<>();
        controlPayload.put("fromUserId", null);
        controlPayload.put("toUserId", userId.toString());
        controlPayload.put("via", "SESSION_START");

        Event controlEvent = eventStore.append(
                sessionId,
                "CONTROL_TAKEN",
                ActorKind.USER,
                userId.toString(),
                userDisplayName,
                null,
                controlPayload
        );

        // 9. Register agent startup AFTER transaction commit
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    agentRunner.runSessionAsync(sessionId);
                }
            });
        } else {
            agentRunner.runSessionAsync(sessionId);
        }

        return new SessionResponse(
                sessionId,
                req.ticketId(),
                agentType,
                scenario,
                SessionStatus.RUNNING.name(),
                userId,
                controlEvent.seq(), // seq 2
                stepBudget,
                tokenBudget,
                now,
                null
        );
    }

    public SessionResponse getSession(UUID orgId, UUID sessionId) {
        Session s = sessionRepository.findById(orgId, sessionId).orElseThrow(() ->
                new ApiException("UNKNOWN_SESSION", "Session does not exist", HttpStatus.NOT_FOUND)
        );
        return SessionResponse.from(s);
    }

    public List<SessionResponse> listSessions(UUID orgId, int limit) {
        int queryLimit = Math.min(Math.max(limit, 1), 100);
        return sessionRepository.findAllByOrg(orgId, queryLimit).stream()
                .map(SessionResponse::from)
                .toList();
    }

    public EventsResponse getEvents(UUID orgId, UUID sessionId, long fromSeq, int limit) {
        Session s = sessionRepository.findById(orgId, sessionId).orElseThrow(() ->
                new ApiException("UNKNOWN_SESSION", "Session does not exist", HttpStatus.NOT_FOUND)
        );

        if (fromSeq < 0) {
            throw new ApiException("VALIDATION_FAILED", "fromSeq cannot be negative", HttpStatus.BAD_REQUEST);
        }

        if (fromSeq > s.lastSeq()) {
            throw new ApiException("INVALID_SEQUENCE", "fromSeq is greater than lastSeq", HttpStatus.BAD_REQUEST);
        }

        int queryLimit = Math.min(Math.max(limit, 1), 500);
        List<Event> events = eventStore.getEvents(sessionId, fromSeq, queryLimit + 1);

        boolean hasMore = events.size() > queryLimit;
        List<Event> result = hasMore ? events.subList(0, queryLimit) : events;

        return new EventsResponse(sessionId, result, s.lastSeq(), hasMore);
    }

    private boolean isTicketUniqueConstraintViolation(Exception e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("sessions_active_ticket_uq") || msg.contains("duplicate key"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}

package com.handoff.agent;

import com.handoff.events.ActorKind;
import com.handoff.events.EventStore;
import com.handoff.session.Session;
import com.handoff.session.SessionRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes the agent loop for a session and guarantees that any unexpected failure
 * appends ERROR and SESSION_FAILED events so sessions are never left RUNNING (Requirement 5).
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final SessionRepository sessionRepository;
    private final Agent agent;
    private final EventStore eventStore;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final Executor executor = ForkJoinPool.commonPool();

    public AgentRunner(
            SessionRepository sessionRepository,
            Agent agent,
            EventStore eventStore,
            org.springframework.transaction.support.TransactionTemplate transactionTemplate
    ) {
        this.sessionRepository = sessionRepository;
        this.agent = agent;
        this.eventStore = eventStore;
        this.transactionTemplate = transactionTemplate;
    }

    public void runSession(UUID sessionId) {
        try {
            Session session = sessionRepository.findByIdWithoutOrg(sessionId)
                    .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));

            if (session.status().isTerminal()) {
                log.info("Session {} is already in terminal state {}, skipping run", sessionId, session.status());
                return;
            }

            agent.execute(session);
        } catch (Exception e) {
            log.error("Agent loop failed unexpectedly for session {}", sessionId, e);
            try {
                transactionTemplate.execute(status -> {
                    eventStore.append(sessionId, "ERROR", ActorKind.SYSTEM, "system", "System", null, Map.of(
                            "code", "INTERNAL",
                            "message", e.getMessage() != null ? e.getMessage() : "Internal agent error",
                            "recoverable", false
                    ));
                    eventStore.append(sessionId, "SESSION_FAILED", ActorKind.SYSTEM, "system", "System", null, Map.of(
                            "reason", "INTERNAL",
                            "message", e.getMessage() != null ? e.getMessage() : "Session failed due to internal error"
                    ));
                    return null;
                });
            } catch (Exception appendEx) {
                log.error("Failed to append terminal error events for session {}", sessionId, appendEx);
            }
        }
    }

    public CompletableFuture<Void> runSessionAsync(UUID sessionId) {
        return CompletableFuture.runAsync(() -> runSession(sessionId), executor);
    }
}

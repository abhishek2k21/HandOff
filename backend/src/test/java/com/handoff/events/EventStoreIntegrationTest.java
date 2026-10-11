package com.handoff.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handoff.AbstractIntegrationTest;
import com.handoff.common.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class EventStoreIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventStore eventStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Test
    void t1_fiftyConcurrentThreadsProduceGaplessSequence() throws InterruptedException {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Set<Long> assignedSeqs = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Event event = transactionTemplate.execute(status ->
                            eventStore.append(
                                    sessionId,
                                    "AGENT_TEXT",
                                    ActorKind.AGENT,
                                    "agent",
                                    "Agent",
                                    null,
                                    Map.of("messageId", "m-1", "text", "msg-" + index, "final", false)
                            )
                    );
                    assignedSeqs.add(event.seq());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete in time");
        executor.shutdown();

        assertTrue(errors.isEmpty(), "No append errors should occur: " + errors);
        assertEquals(50, assignedSeqs.size(), "Exactly 50 unique seq numbers should be generated");

        // Verify gapless 1..50
        for (long expected = 1; expected <= 50; expected++) {
            assertTrue(assignedSeqs.contains(expected), "Missing sequence number: " + expected);
        }

        Long dbLastSeq = jdbc.queryForObject("SELECT last_seq FROM sessions WHERE id = ?", Long.class, sessionId);
        assertEquals(50L, dbLastSeq);
    }

    @Test
    void t2_databaseTriggerRejectsUpdateAndDeleteOnEvents() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);

        Event event = transactionTemplate.execute(status ->
                eventStore.append(
                        sessionId,
                        "AGENT_TEXT",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("text", "immutable text")
                )
        );

        DataAccessException updateEx = assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE events SET type = 'MUTATED' WHERE session_id = ? AND seq = ?",
                        sessionId, event.seq())
        );
        assertTrue(updateEx.getMessage().contains("events are append-only"));

        DataAccessException deleteEx = assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM events WHERE session_id = ? AND seq = ?",
                        sessionId, event.seq())
        );
        assertTrue(deleteEx.getMessage().contains("events are append-only"));
    }

    @Test
    void appendWithoutTransactionThrowsIllegalTransactionStateException() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);

        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () ->
                eventStore.append(
                        sessionId,
                        "AGENT_TEXT",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("text", "no tx")
                )
        );
    }

    @Test
    void rollbackPublishesNothingToRedisStream() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);
        String streamKey = "hg:s:" + sessionId + ":events";

        assertThrows(RuntimeException.class, () ->
                transactionTemplate.execute(status -> {
                    eventStore.append(
                            sessionId,
                            "AGENT_TEXT",
                            ActorKind.AGENT,
                            "agent",
                            "Agent",
                            null,
                            Map.of("text", "will rollback")
                    );
                    throw new RuntimeException("Forced rollback");
                })
        );

        Long streamLen = redisTemplate.opsForStream().size(streamKey);
        assertTrue(streamLen == null || streamLen == 0L, "Rolled-back transaction must publish nothing to Redis stream");
    }

    @Test
    void rollbackReleasesSequenceWithNoGaps() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);

        // Append seq 1 successfully
        Event event1 = transactionTemplate.execute(status ->
                eventStore.append(
                        sessionId,
                        "AGENT_TEXT",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("text", "first")
                )
        );
        assertEquals(1L, event1.seq());

        // A transaction increments last_seq but then rolls back
        assertThrows(RuntimeException.class, () ->
                transactionTemplate.execute(status -> {
                    eventStore.append(
                            sessionId,
                            "AGENT_TEXT",
                            ActorKind.AGENT,
                            "agent",
                            "Agent",
                            null,
                            Map.of("text", "failing")
                    );
                    throw new RuntimeException("Simulated transaction rollback");
                })
        );

        // The session row last_seq rolled back to 1
        Long dbLastSeq = jdbc.queryForObject("SELECT last_seq FROM sessions WHERE id = ?", Long.class, sessionId);
        assertEquals(1L, dbLastSeq);

        // Next successful append gets gapless seq 2
        Event event2 = transactionTemplate.execute(status ->
                eventStore.append(
                        sessionId,
                        "AGENT_TEXT",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("text", "second")
                )
        );
        assertEquals(2L, event2.seq());
    }

    @Test
    void terminalSessionRejectsFurtherAppends() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);

        transactionTemplate.execute(status ->
                eventStore.append(
                        sessionId,
                        "SESSION_COMPLETED",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("outcome", "RESOLVED", "summary", "Done")
                )
        );

        // Further append must fail with SESSION_ENDED (409)
        ApiException ex = assertThrows(ApiException.class, () ->
                transactionTemplate.execute(status ->
                        eventStore.append(
                                sessionId,
                                "AGENT_TEXT",
                                ActorKind.AGENT,
                                "agent",
                                "Agent",
                                null,
                                Map.of("text", "after completed")
                        )
                )
        );
        assertEquals("SESSION_ENDED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void payloadExceeding64KbRejectedWithoutConsumingSeq() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId);
        UUID sessionId = createTestSession(orgId, userId);

        String largeText = "x".repeat(65 * 1024); // 65 KB string
        Map<String, Object> largePayload = Map.of("largeField", largeText);

        ApiException ex = assertThrows(ApiException.class, () ->
                transactionTemplate.execute(status ->
                        eventStore.append(
                                sessionId,
                                "AGENT_TEXT",
                                ActorKind.AGENT,
                                "agent",
                                "Agent",
                                null,
                                largePayload
                        )
                )
        );

        assertEquals("VALIDATION_FAILED", ex.getCode());
        assertEquals(400, ex.getStatus().value());

        // Verify last_seq remains 0
        Long lastSeq = jdbc.queryForObject("SELECT last_seq FROM sessions WHERE id = ?", Long.class, sessionId);
        assertEquals(0L, lastSeq);
    }

    private UUID createTestOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Test Org " + orgId);
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

    private UUID createTestSession(UUID orgId, UUID userId) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, 'T-TEST', 'SCRIPTED', 'RUNNING', 0, 20, 20000, ?)
            """, sessionId, orgId, userId);
        return sessionId;
    }
}

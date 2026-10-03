package com.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that Flyway ran successfully and the V1 migration created
 * the expected tables and trigger.
 */
class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allFourTablesExist() {
        // information_schema.tables lists every table in the database.
        // We query it once per expected table.
        for (String table : new String[]{"sessions", "events", "approvals", "commands"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, count, "Table '" + table + "' should exist");
        }
    }

    @Test
    void eventsCannotBeUpdated() {
        // Insert a session and an event, then try to UPDATE the event.
        // The trigger should block it.
        UUID sessionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, 'T-1', 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
            """, sessionId, orgId, userId);

        jdbc.update("""
            INSERT INTO events (session_id, seq, type, actor_kind, actor_id, payload)
            VALUES (?, 1, 'SESSION_STARTED', 'USER', ?, '{}')
            """, sessionId, userId.toString());

        // UPDATE must be blocked by the trigger
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE events SET type = 'HACKED' WHERE session_id = ? AND seq = 1",
                        sessionId));
    }

    @Test
    void eventsCannotBeDeleted() {
        // Same setup: insert, then try DELETE.
        UUID sessionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, 'T-2', 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
            """, sessionId, orgId, userId);

        jdbc.update("""
            INSERT INTO events (session_id, seq, type, actor_kind, actor_id, payload)
            VALUES (?, 1, 'SESSION_STARTED', 'USER', ?, '{}')
            """, sessionId, userId.toString());

        assertThrows(DataAccessException.class, () ->
            jdbc.update("DELETE FROM events WHERE session_id = ? AND seq = 1",
                        sessionId));
    }

    @Test
    void duplicateApprovalRejected() {
        // Enforces Rule R3 / schema constraint: only one approval row per (session_id, tool_call_id).
        UUID sessionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, 'T-3', 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
            """, sessionId, orgId, userId);

        String toolCallId = "call_refund_123";
        UUID approval1Id = UUID.randomUUID();
        UUID approval2Id = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO approvals (id, session_id, tool_call_id, status, expires_at)
            VALUES (?, ?, ?, 'PENDING', now() + interval '5 minutes')
            """, approval1Id, sessionId, toolCallId);

        // A second approval request for the same tool call must be rejected by approvals_session_tool_uq
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO approvals (id, session_id, tool_call_id, status, expires_at)
                VALUES (?, ?, ?, 'PENDING', now() + interval '5 minutes')
                """, approval2Id, sessionId, toolCallId));
    }

    @Test
    void duplicateEventSeqRejected() {
        // Enforces Rule R1: (session_id, seq) is unique with no duplicate seq permitted.
        UUID sessionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, 'T-4', 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
            """, sessionId, orgId, userId);

        jdbc.update("""
            INSERT INTO events (session_id, seq, type, actor_kind, actor_id, payload)
            VALUES (?, 1, 'SESSION_STARTED', 'USER', ?, '{}')
            """, sessionId, userId.toString());

        // A second event with the exact same seq must violate the (session_id, seq) primary key
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO events (session_id, seq, type, actor_kind, actor_id, payload)
                VALUES (?, 1, 'AGENT_TEXT', 'AGENT', 'agent-1', '{"text":"hi"}')
                """, sessionId));
    }
}


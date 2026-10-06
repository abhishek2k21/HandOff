package com.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that Flyway ran successfully and created V1 and V2 tables,
 * triggers, and foreign key constraints.
 */
class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allTablesExist() {
        String[] expectedTables = {
            "sessions", "events", "approvals", "commands",
            "organizations", "users", "memberships", "refresh_tokens",
            "orders", "tickets", "ticket_notes", "refunds"
        };
        for (String table : expectedTables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, count, "Table '" + table + "' should exist");
        }
    }

    @Test
    void sessionWithUnknownOrganizationIsRejected() {
        UUID sessionId = UUID.randomUUID();
        UUID nonExistentOrgId = UUID.randomUUID();
        UUID userId = createTestUser();

        // Foreign key constraint fk_sessions_organization must reject this insert
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                      last_seq, step_budget, token_budget, created_by)
                VALUES (?, ?, 'T-FK', 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
                """, sessionId, nonExistentOrgId, userId));
    }

    @Test
    void eventsCannotBeUpdated() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser();
        UUID sessionId = UUID.randomUUID();

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
        UUID orgId = createTestOrg();
        UUID userId = createTestUser();
        UUID sessionId = UUID.randomUUID();

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
        UUID orgId = createTestOrg();
        UUID userId = createTestUser();
        UUID sessionId = UUID.randomUUID();

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
        UUID orgId = createTestOrg();
        UUID userId = createTestUser();
        UUID sessionId = UUID.randomUUID();

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

    @Test
    void ticketWithInvalidCompositeOrderForeignKeyRejected() {
        UUID orgId = createTestOrg();
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO tickets (organization_id, id, customer_name, customer_email, subject, description, status, order_id)
                VALUES (?, 'T-999', 'Customer', 'cust@example.com', 'Subj', 'Desc', 'OPEN', 'NON_EXISTENT_ORDER')
                """, orgId));
    }

    @Test
    void activeSessionUniqueConstraintBlocksSecondActiveSession() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser();
        UUID session1Id = UUID.randomUUID();
        UUID session2Id = UUID.randomUUID();
        String ticketId = "T-ACTIVE";

        // Insert first active session
        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, ?, 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
            """, session1Id, orgId, ticketId, userId);

        // Second active session for same ticket must fail unique constraint sessions_active_ticket_uq
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                      last_seq, step_budget, token_budget, created_by)
                VALUES (?, ?, ?, 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
                """, session2Id, orgId, ticketId, userId));

        // Once session1 is ended (ended_at is NOT NULL), second session can be inserted
        jdbc.update("UPDATE sessions SET ended_at = now() WHERE id = ?", session1Id);

        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, ?, 'SCRIPTED', 'RUNNING', 1, 20, 20000, ?)
            """, session2Id, orgId, ticketId, userId);
    }

    private UUID createTestOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Org " + orgId);
        return orgId;
    }

    private UUID createTestUser() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, 'hash', 'Test User')",
                userId, "user-" + userId + "@example.com"
        );
        return userId;
    }
}

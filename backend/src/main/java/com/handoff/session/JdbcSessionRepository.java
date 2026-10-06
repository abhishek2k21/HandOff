package com.handoff.session;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Session> sessionRowMapper = (ResultSet rs, int rowNum) -> new Session(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("organization_id")),
            rs.getString("ticket_id"),
            rs.getString("agent_type"),
            rs.getString("scenario"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getString("controller_user_id") != null ? UUID.fromString(rs.getString("controller_user_id")) : null,
            rs.getLong("last_seq"),
            rs.getInt("step_budget"),
            rs.getInt("token_budget"),
            rs.getInt("steps_used"),
            rs.getInt("tokens_used"),
            UUID.fromString(rs.getString("created_by")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("ended_at") != null ? rs.getTimestamp("ended_at").toInstant() : null
    );

    @Override
    public void createSession(Session session) {
        String sql = """
            INSERT INTO sessions (
                id, organization_id, ticket_id, agent_type, scenario, status,
                controller_user_id, last_seq, step_budget, token_budget, steps_used,
                tokens_used, created_by, created_at, ended_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbc.update(sql,
                session.id(),
                session.organizationId(),
                session.ticketId(),
                session.agentType(),
                session.scenario(),
                session.status().name(),
                session.controllerUserId(),
                session.lastSeq(),
                session.stepBudget(),
                session.tokenBudget(),
                session.stepsUsed(),
                session.tokensUsed(),
                session.createdBy(),
                Timestamp.from(session.createdAt()),
                session.endedAt() != null ? Timestamp.from(session.endedAt()) : null
        );
    }

    @Override
    public Optional<Session> findById(UUID orgId, UUID sessionId) {
        String sql = """
            SELECT id, organization_id, ticket_id, agent_type, scenario, status,
                   controller_user_id, last_seq, step_budget, token_budget, steps_used,
                   tokens_used, created_by, created_at, ended_at
              FROM sessions
             WHERE organization_id = ? AND id = ?
            """;
        List<Session> results = jdbc.query(sql, sessionRowMapper, orgId, sessionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<Session> findByIdWithoutOrg(UUID sessionId) {
        String sql = """
            SELECT id, organization_id, ticket_id, agent_type, scenario, status,
                   controller_user_id, last_seq, step_budget, token_budget, steps_used,
                   tokens_used, created_by, created_at, ended_at
              FROM sessions
             WHERE id = ?
            """;
        List<Session> results = jdbc.query(sql, sessionRowMapper, sessionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Session> findAllByOrg(UUID orgId, int limit) {
        String sql = """
            SELECT id, organization_id, ticket_id, agent_type, scenario, status,
                   controller_user_id, last_seq, step_budget, token_budget, steps_used,
                   tokens_used, created_by, created_at, ended_at
              FROM sessions
             WHERE organization_id = ?
             ORDER BY created_at DESC
             LIMIT ?
            """;
        return jdbc.query(sql, sessionRowMapper, orgId, limit);
    }

    @Override
    public boolean hasActiveSessionForTicket(UUID orgId, String ticketId) {
        String sql = """
            SELECT COUNT(*)
              FROM sessions
             WHERE organization_id = ? AND ticket_id = ? AND ended_at IS NULL
            """;
        Integer count = jdbc.queryForObject(sql, Integer.class, orgId, ticketId);
        return count != null && count > 0;
    }
}

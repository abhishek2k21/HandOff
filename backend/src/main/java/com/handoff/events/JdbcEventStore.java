package com.handoff.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.common.ApiException;
import com.handoff.session.SessionStatus;
import com.handoff.session.SessionStatusReducer;
import com.handoff.ws.RedisStreamPublisher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcEventStore implements EventStore {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024; // 64 KB

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SessionStatusReducer sessionStatusReducer;
    private final RedisStreamPublisher redisStreamPublisher;
    private final RowMapper<Event> eventRowMapper;
    private volatile RuntimeException testGetEventsException = null;

    public void setTestGetEventsException(RuntimeException ex) {
        this.testGetEventsException = ex;
    }

    public JdbcEventStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SessionStatusReducer sessionStatusReducer,
            RedisStreamPublisher redisStreamPublisher
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sessionStatusReducer = sessionStatusReducer;
        this.redisStreamPublisher = redisStreamPublisher;
        this.eventRowMapper = (ResultSet rs, int rowNum) -> {
            try {
                String payloadJson = rs.getString("payload");
                Map<String, Object> payload = payloadJson != null
                        ? objectMapper.readValue(payloadJson, new TypeReference<>() {})
                        : Collections.emptyMap();

                Actor actor = new Actor(
                        ActorKind.valueOf(rs.getString("actor_kind")),
                        rs.getString("actor_id"),
                        rs.getString("actor_name")
                );

                return new Event(
                        UUID.fromString(rs.getString("session_id")),
                        rs.getLong("seq"),
                        rs.getString("type"),
                        actor,
                        rs.getString("command_id"),
                        payload,
                        rs.getTimestamp("created_at").toInstant()
                );
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize event payload", e);
            }
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Event append(
            UUID sessionId,
            String type,
            ActorKind actorKind,
            String actorId,
            String actorName,
            String commandId,
            Map<String, Object> payload
    ) {
        // Enforce 64 KB payload size limit BEFORE touching the database
        byte[] payloadBytes;
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload != null ? payload : Collections.emptyMap());
            payloadBytes = payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new ApiException("VALIDATION_FAILED", "Invalid event payload structure", HttpStatus.BAD_REQUEST);
        }

        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new ApiException("VALIDATION_FAILED", "Event payload exceeds 64 KB limit", HttpStatus.BAD_REQUEST,
                    Map.of("payloadBytes", payloadBytes.length, "limitBytes", MAX_PAYLOAD_BYTES));
        }

        // Determine if event type changes session status
        boolean changesStatus = isStatusChangingEvent(type);
        List<Long> seqList;

        if (changesStatus) {
            // Need current status to reduce
            String selectSql = "SELECT status, ended_at FROM sessions WHERE id = ? FOR UPDATE";
            List<Map<String, Object>> rows = jdbc.queryForList(selectSql, sessionId);
            if (rows.isEmpty()) {
                throw new ApiException("UNKNOWN_SESSION", "Session does not exist", HttpStatus.NOT_FOUND);
            }
            Map<String, Object> sessionRow = rows.get(0);
            if (sessionRow.get("ended_at") != null) {
                throw new ApiException("SESSION_ENDED", "The session is COMPLETED or FAILED", HttpStatus.CONFLICT);
            }

            SessionStatus currentStatus = SessionStatus.valueOf((String) sessionRow.get("status"));
            SessionStatus newStatus = sessionStatusReducer.reduce(currentStatus, type);
            Timestamp endedAtTimestamp = newStatus.isTerminal() ? Timestamp.from(Instant.now()) : null;

            String updateSql = """
                UPDATE sessions
                   SET last_seq = last_seq + 1,
                       status = ?,
                       ended_at = ?
                 WHERE id = ? AND ended_at IS NULL
                RETURNING last_seq;
                """;
            seqList = jdbc.query(updateSql, (rs, rowNum) -> rs.getLong("last_seq"),
                    newStatus.name(), endedAtTimestamp, sessionId);
        } else {
            // Direct atomic seq increment (Rule R1)
            String updateSql = """
                UPDATE sessions
                   SET last_seq = last_seq + 1
                 WHERE id = ? AND ended_at IS NULL
                RETURNING last_seq;
                """;
            seqList = jdbc.query(updateSql, (rs, rowNum) -> rs.getLong("last_seq"), sessionId);
        }

        if (seqList.isEmpty()) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, sessionId);
            if (exists == null || exists == 0) {
                throw new ApiException("UNKNOWN_SESSION", "Session does not exist", HttpStatus.NOT_FOUND);
            }
            throw new ApiException("SESSION_ENDED", "The session is COMPLETED or FAILED", HttpStatus.CONFLICT);
        }

        long seq = seqList.get(0);

        // Insert event into append-only log
        String insertSql = """
            INSERT INTO events (session_id, seq, type, actor_kind, actor_id, actor_name, command_id, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            RETURNING created_at;
            """;

        Timestamp createdAtTs = jdbc.queryForObject(insertSql, Timestamp.class,
                sessionId, seq, type, actorKind.name(), actorId, actorName, commandId, payloadJson);

        Instant createdAt = createdAtTs != null ? createdAtTs.toInstant() : Instant.now();
        Actor actor = new Actor(actorKind, actorId, actorName);

        Event event = new Event(sessionId, seq, type, actor, commandId, payload != null ? payload : Collections.emptyMap(), createdAt);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisStreamPublisher.publish(event);
            }
        });

        return event;
    }

    @Override
    public List<Event> getEvents(UUID sessionId, long fromSeq, int limit) {
        RuntimeException ex = testGetEventsException;
        if (ex != null) {
            testGetEventsException = null;
            throw ex;
        }
        String sql = """
            SELECT session_id, seq, type, actor_kind, actor_id, actor_name, command_id, payload, created_at
              FROM events
             WHERE session_id = ? AND seq > ?
             ORDER BY seq ASC
             LIMIT ?
            """;
        return jdbc.query(sql, eventRowMapper, sessionId, fromSeq, limit);
    }

    private boolean isStatusChangingEvent(String type) {
        return switch (type) {
            case "SESSION_STARTED", "RESUMED", "APPROVAL_DECIDED", "PAUSED",
                 "APPROVAL_REQUESTED", "HANDOFF_SUMMARY", "SESSION_COMPLETED", "SESSION_FAILED" -> true;
            default -> false;
        };
    }
}

package com.handoff.ticket;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Ticket> rowMapper = (ResultSet rs, int rowNum) -> new Ticket(
            UUID.fromString(rs.getString("organization_id")),
            rs.getString("id"),
            rs.getString("customer_name"),
            rs.getString("customer_email"),
            rs.getString("subject"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("order_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    @Override
    public Optional<Ticket> findById(UUID orgId, String ticketId) {
        String sql = """
            SELECT organization_id, id, customer_name, customer_email, subject, description,
                   status, order_id, created_at, updated_at
              FROM tickets
             WHERE organization_id = ? AND id = ?
            """;
        List<Ticket> results = jdbc.query(sql, rowMapper, orgId, ticketId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Ticket> findAllByOrg(UUID orgId) {
        String sql = """
            SELECT organization_id, id, customer_name, customer_email, subject, description,
                   status, order_id, created_at, updated_at
              FROM tickets
             WHERE organization_id = ?
             ORDER BY id ASC
            """;
        return jdbc.query(sql, rowMapper, orgId);
    }

    @Override
    public void createTicket(Ticket ticket) {
        String sql = """
            INSERT INTO tickets (organization_id, id, customer_name, customer_email, subject,
                                 description, status, order_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (organization_id, id) DO NOTHING
            """;
        jdbc.update(sql,
                ticket.organizationId(),
                ticket.id(),
                ticket.customerName(),
                ticket.customerEmail(),
                ticket.subject(),
                ticket.description(),
                ticket.status(),
                ticket.orderId(),
                Timestamp.from(ticket.createdAt()),
                Timestamp.from(ticket.updatedAt())
        );
    }

    @Override
    public void updateStatus(UUID orgId, String ticketId, String status) {
        String sql = """
            UPDATE tickets
               SET status = ?, updated_at = now()
             WHERE organization_id = ? AND id = ?
            """;
        jdbc.update(sql, status, orgId, ticketId);
    }

    @Override
    public void addNote(UUID orgId, String ticketId, String author, String text) {
        String sql = """
            INSERT INTO ticket_notes (id, organization_id, ticket_id, author, text, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            """;
        jdbc.update(sql, UUID.randomUUID(), orgId, ticketId, author, text);
    }

    @Override
    public List<Map<String, Object>> getNotes(UUID orgId, String ticketId) {
        String sql = """
            SELECT id, author, text, created_at
              FROM ticket_notes
             WHERE organization_id = ? AND ticket_id = ?
             ORDER BY created_at ASC
            """;
        return jdbc.queryForList(sql, orgId, ticketId);
    }

    @Override
    public void resetTicketsForDemo(UUID orgId, List<String> ticketIds) {
        if (ticketIds.isEmpty()) {
            return;
        }
        String inSql = String.join(",", Collections.nCopies(ticketIds.size(), "?"));
        Object[] params = new Object[ticketIds.size() + 1];
        params[0] = orgId;
        for (int i = 0; i < ticketIds.size(); i++) {
            params[i + 1] = ticketIds.get(i);
        }

        jdbc.update(
            "DELETE FROM ticket_notes WHERE organization_id = ? AND ticket_id IN (" + inSql + ")",
            params
        );

        jdbc.update(
            "UPDATE tickets SET status = 'OPEN', updated_at = now() WHERE organization_id = ? AND id IN (" + inSql + ")",
            params
        );
    }
}

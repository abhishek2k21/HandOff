package com.handoff.order;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository implements OrderRepository {

    private final JdbcTemplate jdbc;

    public JdbcOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Order> rowMapper = (ResultSet rs, int rowNum) -> new Order(
            UUID.fromString(rs.getString("organization_id")),
            rs.getString("id"),
            rs.getString("customer_name"),
            rs.getString("customer_email"),
            rs.getString("items"),
            rs.getInt("total_amount"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
    );

    @Override
    public Optional<Order> findById(UUID orgId, String orderId) {
        String sql = """
            SELECT organization_id, id, customer_name, customer_email, items::text,
                   total_amount, currency, status, created_at
              FROM orders
             WHERE organization_id = ? AND id = ?
            """;
        List<Order> results = jdbc.query(sql, rowMapper, orgId, orderId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void createOrder(Order order) {
        String sql = """
            INSERT INTO orders (organization_id, id, customer_name, customer_email, items,
                                total_amount, currency, status, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (organization_id, id) DO NOTHING
            """;
        jdbc.update(sql,
                order.organizationId(),
                order.id(),
                order.customerName(),
                order.customerEmail(),
                order.itemsJson(),
                order.totalAmount(),
                order.currency(),
                order.status(),
                Timestamp.from(order.createdAt())
        );
    }
}

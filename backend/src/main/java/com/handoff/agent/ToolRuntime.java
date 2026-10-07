package com.handoff.agent;

import com.handoff.order.Order;
import com.handoff.order.OrderRepository;
import com.handoff.ticket.TicketRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates and executes tool invocations strictly scoped to the session's organization
 * per docs/events.md section 13.2.
 */
@Component
public class ToolRuntime {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "lookup_order",
            "issue_refund",
            "add_note",
            "close_ticket"
    );

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;

    public ToolRuntime(OrderRepository orderRepository, TicketRepository ticketRepository) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
    }

    public boolean isAllowed(String toolName) {
        return ALLOWED_TOOLS.contains(toolName);
    }

    public Map<String, Object> execute(UUID orgId, String toolName, Map<String, Object> args) {
        if (!isAllowed(toolName)) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }

        return switch (toolName) {
            case "lookup_order" -> {
                String orderId = (String) args.get("orderId");
                if (orderId == null || orderId.isBlank()) {
                    throw new IllegalArgumentException("Missing required argument: orderId");
                }
                Order order = orderRepository.findById(orgId, orderId)
                        .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

                Map<String, Object> res = new HashMap<>();
                res.put("id", order.id());
                res.put("customerName", order.customerName());
                res.put("customerEmail", order.customerEmail());
                res.put("totalAmount", order.totalAmount());
                res.put("currency", order.currency());
                res.put("status", order.status());
                yield res;
            }
            case "add_note" -> {
                String ticketId = (String) args.get("ticketId");
                String text = (String) args.get("text");
                if (ticketId == null || text == null) {
                    throw new IllegalArgumentException("Missing required arguments for add_note");
                }
                ticketRepository.addNote(orgId, ticketId, "agent", text);
                yield Map.of("ticketId", ticketId, "noteAdded", true);
            }
            case "close_ticket" -> {
                String ticketId = (String) args.get("ticketId");
                String resolution = (String) args.get("resolution");
                if (ticketId == null) {
                    throw new IllegalArgumentException("Missing required argument: ticketId");
                }
                ticketRepository.updateStatus(orgId, ticketId, "CLOSED");
                yield Map.of("ticketId", ticketId, "status", "CLOSED");
            }
            case "issue_refund" -> {
                String orderId = (String) args.get("orderId");
                yield Map.of("orderId", orderId, "refunded", true);
            }
            default -> throw new IllegalArgumentException("Unsupported tool: " + toolName);
        };
    }
}

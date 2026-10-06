package com.handoff.demo;

import com.handoff.auth.AuthRepository;
import com.handoff.order.Order;
import com.handoff.order.OrderRepository;
import com.handoff.ticket.Ticket;
import com.handoff.ticket.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds demo orders and tickets for development, and resets them to OPEN on restart (Requirement 7).
 * Active ONLY under the "dev" profile. Never runs in tests.
 */
@Component
@Profile("dev")
@org.springframework.core.annotation.Order(2)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_ORG_NAME = "Acme Support";

    private final AuthRepository authRepository;
    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final boolean enabled;
    private final boolean resetOnStart;

    public DemoDataSeeder(
            AuthRepository authRepository,
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            @Value("${handoff.demo-data.enabled:false}") boolean enabled,
            @Value("${handoff.demo-data.reset-on-start:false}") boolean resetOnStart
    ) {
        this.authRepository = authRepository;
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.enabled = enabled;
        this.resetOnStart = resetOnStart;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        UUID orgId = authRepository.findOrganizationByName(DEMO_ORG_NAME)
                .map(com.handoff.auth.AuthRepository.OrganizationRecord::id)
                .orElse(null);

        if (orgId == null) {
            log.warn("Demo organization '{}' not found, skipping demo data seeding", DEMO_ORG_NAME);
            return;
        }

        List<String> demoTicketIds = List.of(
                "T-101", "T-102", "T-103", "T-104", "T-105", "T-106", "T-107", "T-108"
        );

        if (resetOnStart) {
            log.info("Resetting demo tickets {} to OPEN and clearing notes...", demoTicketIds);
            ticketRepository.resetTicketsForDemo(orgId, demoTicketIds);
        }

        seedOrders(orgId);
        seedTickets(orgId);
        log.info("Demo data seeding completed for '{}'", DEMO_ORG_NAME);
    }

    private void seedOrders(UUID orgId) {
        Instant now = Instant.now();

        orderRepository.createOrder(new Order(
                orgId,
                "8841",
                "Aarav Sharma",
                "aarav@example.com",
                "[{\"sku\":\"SKU-99\",\"name\":\"Wireless Noise-Canceling Headphones\",\"qty\":1,\"price\":4200}]",
                4200,
                "INR",
                "DELIVERED",
                now
        ));

        orderRepository.createOrder(new Order(
                orgId,
                "8842",
                "Priya Singh",
                "priya@example.com",
                "[{\"sku\":\"SKU-42\",\"name\":\"USB-C Fast Charging Hub\",\"qty\":1,\"price\":1500}]",
                1500,
                "INR",
                "DELIVERED",
                now
        ));

        orderRepository.createOrder(new Order(
                orgId,
                "8843",
                "Rahul Verma",
                "rahul@example.com",
                "[{\"sku\":\"SKU-101\",\"name\":\"Mechanical Keyboard\",\"qty\":1,\"price\":8900}]",
                8900,
                "INR",
                "SHIPPED",
                now
        ));

        orderRepository.createOrder(new Order(
                orgId,
                "8844",
                "Sneha Patel",
                "sneha@example.com",
                "[{\"sku\":\"SKU-55\",\"name\":\"Ergonomic Mouse\",\"qty\":1,\"price\":3100}]",
                3100,
                "INR",
                "CANCELLED",
                now
        ));
    }

    private void seedTickets(UUID orgId) {
        Instant now = Instant.now();

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-101",
                "Aarav Sharma",
                "aarav@example.com",
                "Where is my order #8841?",
                "Customer inquiring about delivery status of order #8841.",
                "OPEN",
                "8841",
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-102",
                "Aarav Sharma",
                "aarav@example.com",
                "Damaged item received for order #8841",
                "The packaging was crushed and headphones do not turn on.",
                "OPEN",
                "8841",
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-103",
                "Priya Singh",
                "priya@example.com",
                "Stream throughput benchmark test",
                "High-frequency streaming benchmark test ticket.",
                "OPEN",
                "8842",
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-104",
                "Rahul Verma",
                "rahul@example.com",
                "Change shipping address for order #8843",
                "Need to update delivery address before dispatch.",
                "OPEN",
                "8843",
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-105",
                "Sneha Patel",
                "sneha@example.com",
                "Refund status on cancelled order #8844",
                "Order was cancelled but bank refund not credited yet.",
                "OPEN",
                "8844",
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-106",
                "Karan Johar",
                "karan@example.com",
                "General warranty question",
                "Customer asking about manufacturer warranty duration.",
                "OPEN",
                null,
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-107",
                "Ananya Roy",
                "ananya@example.com",
                "Billing invoice discrepancy",
                "Customer requesting GST tax invoice with updated entity name.",
                "OPEN",
                null,
                now,
                now
        ));

        ticketRepository.createTicket(new Ticket(
                orgId,
                "T-108",
                "Vikram Malhotra",
                "vikram@example.com",
                "Return policy clarification",
                "Can unboxed accessories be returned within 14 days?",
                "OPEN",
                null,
                now,
                now
        ));
    }
}

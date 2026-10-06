package com.handoff.order;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
    Optional<Order> findById(UUID orgId, String orderId);
    void createOrder(Order order);
}

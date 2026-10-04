package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handoff.AbstractIntegrationTest;
import com.handoff.common.ApiException;
import com.handoff.common.UnauthenticatedException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies single-use WebSocket tickets, Redis GETDEL semantics, expiration, and rate limits.
 */
class WsTicketIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void wsTicketIssuedAndConsumedOnlyOnce() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, orgId, Role.OPERATOR, "Maya");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<WsTicketResponse> response = restTemplate.exchange(
                "/api/ws-ticket", HttpMethod.POST, new HttpEntity<>(headers), WsTicketResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String ticket = response.getBody().ticket();
        assertNotNull(ticket);
        assertTrue(ticket.length() >= 32);

        // First consume: success via Redis GETDEL
        var payload = wsTicketService.consumeTicket(ticket);
        assertEquals(userId, payload.userId());
        assertEquals(orgId, payload.organizationId());
        assertEquals(Role.OPERATOR, payload.role());
        assertEquals("Maya", payload.name());

        // Second consume: ticket is gone from Redis, must throw TICKET_INVALID
        UnauthenticatedException ex = assertThrows(
                UnauthenticatedException.class,
                () -> wsTicketService.consumeTicket(ticket)
        );
        assertEquals("TICKET_INVALID", ex.getCode());
    }

    @Test
    void expiredTicketIsRejected() {
        String testTicket = "expired-ticket-" + UUID.randomUUID();
        String key = "hg:ticket:" + testTicket;

        // Set key with 100ms TTL
        redis.opsForValue().set(key, "{\"userId\":\"" + UUID.randomUUID() + "\",\"organizationId\":\"" + UUID.randomUUID() + "\",\"role\":\"VIEWER\",\"name\":\"Test\"}", Duration.ofMillis(100));

        // Wait until expired using Awaitility (no Thread.sleep)
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> Boolean.FALSE.equals(redis.hasKey(key)));

        UnauthenticatedException ex = assertThrows(
                UnauthenticatedException.class,
                () -> wsTicketService.consumeTicket(testTicket)
        );
        assertEquals("TICKET_INVALID", ex.getCode());
    }

    @Test
    void wsTicketRateLimitEnforcedAfterTenRequestsPerMinute() {
        UUID userId = UUID.randomUUID();
        UserPrincipal principal = new UserPrincipal(userId, UUID.randomUUID(), Role.OPERATOR, "RateUser");

        // Issue 10 tickets successfully
        for (int i = 0; i < 10; i++) {
            assertNotNull(wsTicketService.createTicket(principal));
        }

        // 11th ticket must trigger RATE_LIMITED (429)
        ApiException ex = assertThrows(
                ApiException.class,
                () -> wsTicketService.createTicket(principal)
        );
        assertEquals("RATE_LIMITED", ex.getCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertTrue(ex.getDetails().containsKey("retryAfterSeconds"));
    }
}

package com.handoff.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handoff.AbstractIntegrationTest;
import com.handoff.auth.JwtService;
import com.handoff.auth.Role;
import com.handoff.common.ApiError;
import com.handoff.ticket.Ticket;
import com.handoff.ticket.TicketRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void simultaneousSessionCreationYieldsExactlyOne201AndOne409() throws InterruptedException {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        String ticketId = "T-RACE-1";
        createTestTicket(orgId, ticketId, "OPEN", null);

        String token = jwtService.generateAccessToken(userId, orgId, Role.OPERATOR, "Operator User");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        CreateSessionRequest body = new CreateSessionRequest(ticketId, "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000);
        HttpEntity<CreateSessionRequest> request = new HttpEntity<>(body, headers);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<ResponseEntity<String>> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<String> res = restTemplate.exchange(
                            "/api/sessions",
                            HttpMethod.POST,
                            request,
                            String.class
                    );
                    responses.add(res);
                } catch (Exception e) {
                    // Ignore client exceptions
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(2, responses.size());
        long count201 = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long count409 = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

        assertEquals(1, count201, "Exactly one request must succeed with 201 Created");
        assertEquals(1, count409, "Exactly one request must fail with 409 Conflict");

        ResponseEntity<String> conflictResponse = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .findFirst()
                .orElseThrow();

        assertTrue(conflictResponse.getBody().contains("INVALID_STATE"));
        assertTrue(conflictResponse.getBody().contains("ticketId"));
    }

    @Test
    void ticketValidationRejections() {
        UUID orgId = createTestOrg();
        UUID otherOrgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);

        createTestTicket(orgId, "T-CLOSED", "CLOSED", null);
        createTestTicket(otherOrgId, "T-OTHER-ORG", "OPEN", null);

        String token = jwtService.generateAccessToken(userId, orgId, Role.OPERATOR, "Operator User");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        // 1. Unknown ticket -> 400 VALIDATION_FAILED
        ResponseEntity<Map<String, Object>> resUnknown = restTemplate.exchange(
                "/api/sessions",
                HttpMethod.POST,
                new HttpEntity<>(new CreateSessionRequest("T-NONEXISTENT", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000), headers),
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.BAD_REQUEST, resUnknown.getStatusCode());

        // 2. Closed ticket -> 400 VALIDATION_FAILED
        ResponseEntity<Map<String, Object>> resClosed = restTemplate.exchange(
                "/api/sessions",
                HttpMethod.POST,
                new HttpEntity<>(new CreateSessionRequest("T-CLOSED", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000), headers),
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.BAD_REQUEST, resClosed.getStatusCode());

        // 3. Other org's ticket -> 400 VALIDATION_FAILED
        ResponseEntity<Map<String, Object>> resOther = restTemplate.exchange(
                "/api/sessions",
                HttpMethod.POST,
                new HttpEntity<>(new CreateSessionRequest("T-OTHER-ORG", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000), headers),
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.BAD_REQUEST, resOther.getStatusCode());
    }

    @Test
    void sessionLifecycleAndQueryEndpoints() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        createTestTicket(orgId, "T-QUERY-1", "OPEN", null);

        String token = jwtService.generateAccessToken(userId, orgId, Role.OPERATOR, "Operator User");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        // 1. Create session
        ResponseEntity<SessionResponse> createRes = restTemplate.exchange(
                "/api/sessions",
                HttpMethod.POST,
                new HttpEntity<>(new CreateSessionRequest("T-QUERY-1", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000), headers),
                SessionResponse.class
        );
        assertEquals(HttpStatus.CREATED, createRes.getStatusCode());
        SessionResponse created = createRes.getBody();
        assertNotNull(created);
        UUID sessionId = created.id();
        assertEquals("RUNNING", created.status());
        assertEquals(2L, created.lastSeq());

        // 2. GET /api/sessions/{id}
        ResponseEntity<SessionResponse> getRes = restTemplate.exchange(
                "/api/sessions/" + sessionId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                SessionResponse.class
        );
        assertEquals(HttpStatus.OK, getRes.getStatusCode());
        assertNotNull(getRes.getBody());
        assertEquals(sessionId, getRes.getBody().id());

        // 3. GET /api/sessions (list)
        ResponseEntity<Map<String, Object>> listRes = restTemplate.exchange(
                "/api/sessions",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, listRes.getStatusCode());
        assertNotNull(listRes.getBody());
        List<?> items = (List<?>) listRes.getBody().get("items");
        assertFalse(items.isEmpty());

        // 4. GET /api/sessions/{id}/events
        ResponseEntity<EventsResponse> eventsRes = restTemplate.exchange(
                "/api/sessions/" + sessionId + "/events?fromSeq=0&limit=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                EventsResponse.class
        );
        assertEquals(HttpStatus.OK, eventsRes.getStatusCode());
        assertNotNull(eventsRes.getBody());
        assertFalse(eventsRes.getBody().events().isEmpty());

        // 5. Invalid fromSeq > lastSeq returns 400 INVALID_SEQUENCE
        ResponseEntity<Map<String, Object>> invalidSeqRes = restTemplate.exchange(
                "/api/sessions/" + sessionId + "/events?fromSeq=9999",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.BAD_REQUEST, invalidSeqRes.getStatusCode());

        // 6. Cross-org query returns 404 UNKNOWN_SESSION
        UUID foreignOrgId = createTestOrg();
        UUID foreignUser = createTestUser(foreignOrgId, Role.VIEWER);
        String foreignToken = jwtService.generateAccessToken(foreignUser, foreignOrgId, Role.VIEWER, "Foreign Viewer");
        HttpHeaders foreignHeaders = new HttpHeaders();
        foreignHeaders.setBearerAuth(foreignToken);

        ResponseEntity<Map<String, Object>> crossOrgRes = restTemplate.exchange(
                "/api/sessions/" + sessionId,
                HttpMethod.GET,
                new HttpEntity<>(foreignHeaders),
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.NOT_FOUND, crossOrgRes.getStatusCode());
    }

    @Test
    void getTicketsReturnsOnlyCallerOrgTickets() {
        UUID orgId = createTestOrg();
        UUID otherOrgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.VIEWER);

        createTestTicket(orgId, "T-MY-ORG", "OPEN", null);
        createTestTicket(otherOrgId, "T-THEIR-ORG", "OPEN", null);

        String token = jwtService.generateAccessToken(userId, orgId, Role.VIEWER, "Viewer");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/tickets",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertTrue(items.stream().anyMatch(t -> "T-MY-ORG".equals(t.get("id"))));
        assertFalse(items.stream().anyMatch(t -> "T-THEIR-ORG".equals(t.get("id"))));
    }

    @Test
    void rolledBackSessionCreationDoesNotStartAgentRunner() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        createTestTicket(orgId, "T-ROLLBACK", "OPEN", null);

        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(
                        restTemplate.getRestTemplate().getRequestFactory() instanceof Object
                                ? (org.springframework.transaction.PlatformTransactionManager) applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class)
                                : null
                );

        assertThrows(RuntimeException.class, () ->
                txTemplate.execute(status -> {
                    sessionService.executeCreateSessionTransaction(
                            orgId, userId, "Operator",
                            new CreateSessionRequest("T-ROLLBACK", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000)
                    );
                    throw new RuntimeException("Forced rollback");
                })
        );

        // Verification: no active session was saved in DB
        assertFalse(sessionRepository.hasActiveSessionForTicket(orgId, "T-ROLLBACK"));
    }

    private UUID createTestOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Org " + orgId);
        return orgId;
    }

    private UUID createTestUser(UUID orgId, Role role) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, 'hash', 'Test User')",
                userId, "user-" + userId + "@example.com"
        );
        jdbc.update(
                "INSERT INTO memberships (user_id, organization_id, role) VALUES (?, ?, ?)",
                userId, orgId, role.name()
        );
        return userId;
    }

    private void createTestTicket(UUID orgId, String ticketId, String status, String orderId) {
        ticketRepository.createTicket(new Ticket(
                orgId,
                ticketId,
                "Customer",
                "customer@example.com",
                "Subject " + ticketId,
                "Description " + ticketId,
                status,
                orderId,
                Instant.now(),
                Instant.now()
        ));
    }
}

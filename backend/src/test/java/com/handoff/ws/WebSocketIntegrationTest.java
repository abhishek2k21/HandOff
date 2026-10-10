package com.handoff.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.AbstractIntegrationTest;
import com.handoff.agent.AgentRunner;
import com.handoff.agent.ScriptedAgent;
import com.handoff.agent.ToolExecutionService;
import com.handoff.auth.Role;
import com.handoff.auth.UserPrincipal;
import com.handoff.auth.WsTicketService;
import com.handoff.events.ActorKind;
import com.handoff.events.Event;
import com.handoff.events.EventStore;
import com.handoff.order.Order;
import com.handoff.order.OrderRepository;
import com.handoff.session.CreateSessionRequest;
import com.handoff.session.Session;
import com.handoff.session.SessionRepository;
import com.handoff.session.SessionResponse;
import com.handoff.session.SessionService;
import com.handoff.session.SessionStatus;
import com.handoff.ticket.Ticket;
import com.handoff.ticket.TicketRepository;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class WebSocketIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private HandOffWebSocketHandler handOffWebSocketHandler;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private RedisStreamPublisher redisStreamPublisher;

    @Autowired
    private RedisStreamListener redisStreamListener;

    private final List<TestWsClient> openClients = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        handOffWebSocketHandler.setAuthTimeoutSeconds(5);
        handOffWebSocketHandler.setMaxSubscriptionsPerConnection(20);
        handOffWebSocketHandler.setMaxSubscribersPerSession(200);
        handOffWebSocketHandler.setTestHookAfterRegistrationBeforeReaderActivation(null);
        handOffWebSocketHandler.setTestHookBeforeHistoryRead(null);
        handOffWebSocketHandler.setTestHookBeforeBufferFlush(null);
        redisStreamPublisher.setTestDropFilter(null);
        redisStreamListener.setTestReadException(null);
        reconciliationService.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        handOffWebSocketHandler.setMaxSubscriptionsPerConnection(20);
        handOffWebSocketHandler.setMaxSubscribersPerSession(200);
        redisStreamPublisher.setTestDropFilter(null);
        redisStreamListener.setTestReadException(null);
        for (TestWsClient client : openClients) {
            client.close();
        }
        openClients.clear();
        handOffWebSocketHandler.setTestHookAfterRegistrationBeforeReaderActivation(null);
        handOffWebSocketHandler.setTestHookBeforeHistoryRead(null);
        handOffWebSocketHandler.setTestHookBeforeBufferFlush(null);
    }

    // ==========================================
    // T16 Auth Tests
    // ==========================================

    @Test
    void t16_authTimeoutClosesWith4408() throws Exception {
        handOffWebSocketHandler.setAuthTimeoutSeconds(1); // Set 1 second for fast test execution
        TestWsClient client = connectClient();

        CloseStatus status = client.closeFuture.get(4, TimeUnit.SECONDS);
        assertEquals(4408, status.getCode());
    }

    @Test
    void t16_nonAuthFirstMessageClosesWith4401() throws Exception {
        TestWsClient client = connectClient();
        client.send("{\"op\":\"ping\"}");

        CloseStatus status = client.closeFuture.get(4, TimeUnit.SECONDS);
        assertEquals(4401, status.getCode());
    }

    @Test
    void t16_reusedTicketClosesWith4401() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        // First client uses ticket -> succeeds
        TestWsClient client1 = connectClient();
        client1.send("{\"op\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        JsonNode resp1 = client1.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("auth_ok", resp1.get("op").asText());

        // Second client attempts to reuse same ticket -> closed with 4401
        TestWsClient client2 = connectClient();
        client2.send("{\"op\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        CloseStatus status2 = client2.closeFuture.get(3, TimeUnit.SECONDS);
        assertEquals(4401, status2.getCode());
    }

    @Test
    void t16_invalidOrExpiredTicketClosesWith4401() throws Exception {
        TestWsClient client = connectClient();
        client.send("{\"op\":\"auth\",\"ticket\":\"invalid-ticket-value\"}");

        CloseStatus status = client.closeFuture.get(3, TimeUnit.SECONDS);
        assertEquals(4401, status.getCode());
    }

    @Test
    void authOkReturnsUserPayload() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.ADMIN);
        String ticket = createWsTicket(userId, orgId, Role.ADMIN, "Admin User");

        TestWsClient client = connectAndAuth(ticket);
        JsonNode authMsg = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("auth_ok", authMsg.get("op").asText());

        JsonNode userNode = authMsg.get("user");
        assertNotNull(userNode);
        assertEquals(userId.toString(), userNode.get("id").asText());
        assertEquals("ADMIN", userNode.get("role").asText());
        assertEquals("Admin User", userNode.get("name").asText());
    }

    // ==========================================
    // Subscribe & Isolation Tests
    // ==========================================

    @Test
    void organizationIsolationRejectsCrossOrgSubscriptionWithUnknownSession() throws Exception {
        UUID orgA = createTestOrg();
        UUID userA = createTestUser(orgA, Role.OPERATOR);
        UUID sessionA = createTestSession(orgA, userA);

        UUID orgB = createTestOrg();
        UUID userB = createTestUser(orgB, Role.OPERATOR);
        String ticketB = createWsTicket(userB, orgB, Role.OPERATOR, "Bob");

        TestWsClient clientB = connectAndAuth(ticketB);
        clientB.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // User B tries to subscribe to Session A (which belongs to Org A)
        clientB.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionA + "\",\"fromSeq\":0}");

        JsonNode err = clientB.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("error", err.get("op").asText());
        assertEquals("UNKNOWN_SESSION", err.get("code").asText());
    }

    @Test
    void invalidSequenceRejectsFromSeqGreaterThanLastSeq() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // Subscribe with fromSeq 999 while lastSeq is 0
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":999}");

        JsonNode err = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("error", err.get("op").asText());
        assertEquals("INVALID_SEQUENCE", err.get("code").asText());

        // Connection stays open; valid subscription succeeds
        assertTrue(client.isOpen());
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(subResp);
        assertEquals("subscribed", subResp.get("op").asText());
        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(caughtUp);
        assertEquals("caught_up", caughtUp.get("op").asText());
    }

    @Test
    void viewerCanSubscribeAndReceiveEvents() throws Exception {
        UUID orgId = createTestOrg();
        UUID operatorId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, operatorId);

        UUID viewerId = createTestUser(orgId, Role.VIEWER);
        String viewerTicket = createWsTicket(viewerId, orgId, Role.VIEWER, "Viewer Bob");

        TestWsClient client = connectAndAuth(viewerTicket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", subResp.get("op").asText());

        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp.get("op").asText());

        // Append live event
        Event live = appendEventInTx(sessionId, "AGENT_TEXT", "hello viewer");

        JsonNode liveMsg = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("event", liveMsg.get("op").asText());
        assertEquals(live.seq(), liveMsg.get("event").get("seq").asLong());
        assertEquals("hello viewer", liveMsg.get("event").get("payload").get("text").asText());
    }

    // ==========================================
    // Replay Tests
    // ==========================================

    @Test
    void replay2003EventsInBatchesFollowedByCaughtUp() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        int totalEvents = 2003;
        // Batch insert events directly for fast test setup
        for (int i = 1; i <= totalEvents; i++) {
            final int seq = i;
            transactionTemplate.execute(status -> {
                eventStore.append(
                        sessionId,
                        "AGENT_TEXT",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("messageId", "m-" + seq, "text", "msg-" + seq, "final", false)
                );
                return null;
            });
        }

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", subResp.get("op").asText());
        assertEquals(totalEvents, subResp.get("lastSeq").asLong());

        int receivedEventCount = 0;
        long expectedNextSeq = 1;

        while (true) {
            JsonNode msg = client.nextMessage(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Timed out waiting for replay batch or caught_up");
            String op = msg.get("op").asText();

            if ("events".equals(op)) {
                JsonNode eventsArray = msg.get("events");
                assertTrue(eventsArray.size() <= 500, "Batch size must not exceed 500 events");
                for (JsonNode ev : eventsArray) {
                    long seq = ev.get("seq").asLong();
                    assertEquals(expectedNextSeq, seq, "Events must be strictly sequential and gapless");
                    expectedNextSeq++;
                    receivedEventCount++;
                }
            } else if ("caught_up".equals(op)) {
                assertEquals(totalEvents, msg.get("lastSeq").asLong());
                break;
            } else {
                org.junit.jupiter.api.Assertions.fail("Unexpected op during replay: " + op);
            }
        }

        assertEquals(totalEvents, receivedEventCount);
        assertNull(client.nextMessage(500, TimeUnit.MILLISECONDS), "No messages expected after caught_up on finished session");
    }

    @Test
    void replayFromSeqInMiddleReceivesSubsequentEventsAndCaughtUp() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        int totalEvents = 20;
        for (int i = 1; i <= totalEvents; i++) {
            final int seq = i;
            transactionTemplate.execute(status -> {
                eventStore.append(
                        sessionId,
                        "AGENT_TEXT",
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("text", "msg-" + seq)
                );
                return null;
            });
        }

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // Replay from seq 10
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":10}");
        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", subResp.get("op").asText());

        JsonNode eventsBatch = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", eventsBatch.get("op").asText());
        JsonNode eventsArr = eventsBatch.get("events");
        assertEquals(10, eventsArr.size());
        assertEquals(11, eventsArr.get(0).get("seq").asLong());
        assertEquals(20, eventsArr.get(eventsArr.size() - 1).get("seq").asLong());

        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp.get("op").asText());
        assertEquals(20, caughtUp.get("lastSeq").asLong());
    }

    // ==========================================
    // Re-subscribe Tests
    // ==========================================

    @Test
    void resubscribeReplacesSubscriptionAtomicallyWithNoLostOrDuplicateEvents() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        for (int i = 1; i <= 5; i++) {
            final int seq = i;
            transactionTemplate.execute(status ->
                    eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of("text", "hist-" + seq)));
        }

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // First subscribe
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        JsonNode sub1 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", sub1.get("op").asText());
        JsonNode batch1 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", batch1.get("op").asText());
        JsonNode caughtUp1 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp1.get("op").asText());

        // Re-subscribe for same session with fromSeq = 3
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":3}");
        JsonNode sub2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", sub2.get("op").asText());
        JsonNode batch2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", batch2.get("op").asText());
        JsonNode eventsArr = batch2.get("events");
        assertEquals(2, eventsArr.size()); // seq 4 and 5
        assertEquals(4, eventsArr.get(0).get("seq").asLong());
        assertEquals(5, eventsArr.get(1).get("seq").asLong());

        JsonNode caughtUp2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp2.get("op").asText());

        // Live event published after re-subscribe is delivered once
        Event live = appendEventInTx(sessionId, "AGENT_TEXT", "live-after-resub");
        JsonNode liveMsg = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("event", liveMsg.get("op").asText());
        assertEquals(live.seq(), liveMsg.get("event").get("seq").asLong());
    }

    // ==========================================
    // Race Condition Tests with Hooks
    // ==========================================

    @Test
    void raceHookAfterRegistrationBeforeReaderActivationDeliversEventWithoutLoss() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        CountDownLatch hookLatch = new CountDownLatch(1);

        // Item 1 hook: between registration and reader activation, append an event
        handOffWebSocketHandler.setTestHookAfterRegistrationBeforeReaderActivation(() -> {
            try {
                appendEventInTx(sessionId, "AGENT_TEXT", "appended-in-hook");
                hookLatch.countDown();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");

        assertTrue(hookLatch.await(5, TimeUnit.SECONDS));

        // Client must receive the event appended in hook (either via history replay or live queue),
        // and must not miss it!
        boolean eventReceived = false;
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode msg = client.nextMessage(1, TimeUnit.SECONDS);
            if (msg == null) break;
            String op = msg.get("op").asText();
            if ("events".equals(op)) {
                for (JsonNode ev : msg.get("events")) {
                    if ("appended-in-hook".equals(ev.get("payload").get("text").asText())) {
                        eventReceived = true;
                    }
                }
            } else if ("event".equals(op)) {
                if ("appended-in-hook".equals(msg.get("event").get("payload").get("text").asText())) {
                    eventReceived = true;
                }
            }
        }

        assertTrue(eventReceived, "Event appended between registration and reader activation must be delivered without loss");
    }

    @Test
    void lateDuplicateIsDiscardedWithoutEmittingDuplicateSeq() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        // Pre-create event 1
        Event ev1 = appendEventInTx(sessionId, "AGENT_TEXT", "event-1");

        // Hook before buffer flush: simulate late duplicate by re-publishing event 1 to Redis stream
        handOffWebSocketHandler.setTestHookBeforeBufferFlush(() -> {
            redisTemplate.opsForStream().add(
                    RedisStreamPublisher.getStreamKey(sessionId),
                    Map.of("seq", String.valueOf(ev1.seq()), "data", serializeEvent(ev1))
            );
        });

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");

        // Consume subscribed, events (with ev1), caught_up
        JsonNode sub = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", sub.get("op").asText());

        JsonNode batch = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", batch.get("op").asText());

        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp.get("op").asText());

        // Now append a fresh event 2
        Event ev2 = appendEventInTx(sessionId, "AGENT_TEXT", "event-2");

        JsonNode live = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(live);
        assertEquals("event", live.get("op").asText());
        // Verify live is event 2, not a duplicate event 1
        assertEquals(ev2.seq(), live.get("event").get("seq").asLong());
        assertEquals("event-2", live.get("event").get("payload").get("text").asText());
    }

    @Test
    void raceHookAppendAfterStartBufferingBeforeReadHistoryDeliversEventWithoutLoss() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        // Seed seq 1
        appendEventInTx(sessionId, "AGENT_TEXT", "first-event");

        handOffWebSocketHandler.setTestHookBeforeHistoryRead(() -> {
            // Append seq 2 while buffering has started but history read has not occurred
            appendEventInTx(sessionId, "AGENT_TEXT", "appended-before-history-read");
        });

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");

        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", subResp.get("op").asText());

        JsonNode eventsMsg = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", eventsMsg.get("op").asText());
        assertEquals(2, eventsMsg.get("events").size());

        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp.get("op").asText());
        assertEquals(2L, caughtUp.get("lastSeq").asLong());

        // Nothing more expected (no duplicate delivery)
        assertNull(client.nextMessage(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void raceHookAppendAfterReadHistoryBeforeFlushDeliversLiveEvent() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        // Seed seq 1
        appendEventInTx(sessionId, "AGENT_TEXT", "seed-event");

        handOffWebSocketHandler.setTestHookBeforeBufferFlush(() -> {
            // Append seq 2 after history was read, but before buffer is flushed
            appendEventInTx(sessionId, "AGENT_TEXT", "appended-before-buffer-flush");
        });

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");

        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", subResp.get("op").asText());

        JsonNode eventsMsg = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", eventsMsg.get("op").asText());
        assertEquals(1, eventsMsg.get("events").size());
        assertEquals(1L, eventsMsg.get("events").get(0).get("seq").asLong());

        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp.get("op").asText());

        // Now seq 2 arrives as a live event during queue drain
        JsonNode liveMsg = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(liveMsg, "Event appended before buffer flush must be delivered live");
        assertEquals("event", liveMsg.get("op").asText());
        assertEquals(2L, liveMsg.get("event").get("seq").asLong());
    }

    @Test
    void resubscribeMidstreamWithLateSendFromOldWorkerIsDropped() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        for (int i = 1; i <= 5; i++) {
            appendEventInTx(sessionId, "AGENT_TEXT", "event-" + i);
        }

        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // First subscription fromSeq=0
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        client.nextMessage(3, TimeUnit.SECONDS); // subscribed
        client.nextMessage(3, TimeUnit.SECONDS); // events (1..5)
        client.nextMessage(3, TimeUnit.SECONDS); // caught_up

        // Re-subscribe mid-stream fromSeq=3
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":3}");
        JsonNode subResp2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("subscribed", subResp2.get("op").asText());

        JsonNode eventsMsg2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("events", eventsMsg2.get("op").asText());
        assertEquals(2, eventsMsg2.get("events").size());
        assertEquals(4L, eventsMsg2.get("events").get(0).get("seq").asLong());
        assertEquals(5L, eventsMsg2.get("events").get(1).get("seq").asLong());

        JsonNode caughtUp2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("caught_up", caughtUp2.get("op").asText());
        assertEquals(5L, caughtUp2.get("lastSeq").asLong());
    }

    @Test
    void messageLargerThan16KBClosesWith1009() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // Send payload exceeding 16 KB (16384 bytes)
        String oversized = "{\"op\":\"pong\",\"padding\":\"" + "x".repeat(17000) + "\"}";
        client.send(oversized);

        CloseStatus closeStatus = client.closeFuture.get(5, TimeUnit.SECONDS);
        assertEquals(1009, closeStatus.getCode(), "Payload exceeding 16 KB must close with 1009");
    }

    @Test
    void missingOriginHeaderIsAllowed() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.VIEWER);
        String ticket = createWsTicket(userId, orgId, Role.VIEWER, "Bob");

        // Client without Origin header
        TestWsClient client = connectAndAuth(ticket);
        JsonNode resp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals("auth_ok", resp.get("op").asText());
    }

    @Test
    void exceedingMaxSubscriptionsPerConnectionReturnsValidationFailed() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // Subscribe to 20 distinct sessions
        for (int i = 1; i <= 20; i++) {
            UUID sid = createTestSession(orgId, userId);
            client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
            client.nextMessage(3, TimeUnit.SECONDS); // subscribed
            client.nextMessage(3, TimeUnit.SECONDS); // caught_up
        }

        // 21st subscription should fail with VALIDATION_FAILED
        UUID sid21 = createTestSession(orgId, userId);
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid21 + "\",\"fromSeq\":0}");
        JsonNode err = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(err);
        assertEquals("error", err.get("op").asText());
        assertEquals("VALIDATION_FAILED", err.get("code").asText());
        assertTrue(client.isOpen());
    }

    @Test
    void exceedingMaxSubscribersPerSessionReturnsRateLimited() throws Exception {
        UUID orgId = createTestOrg();
        UUID sid = createTestSession(orgId, createTestUser(orgId, Role.OPERATOR));

        handOffWebSocketHandler.setMaxSubscribersPerSession(3);
        try {
            for (int i = 1; i <= 3; i++) {
                UUID u = createTestUser(orgId, Role.OPERATOR);
                String t = createWsTicket(u, orgId, Role.OPERATOR, "Op " + i);
                TestWsClient c = connectAndAuth(t);
                c.nextMessage(3, TimeUnit.SECONDS); // auth_ok
                c.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
                assertNotNull(c.nextMessage(3, TimeUnit.SECONDS)); // subscribed
                assertNotNull(c.nextMessage(3, TimeUnit.SECONDS)); // caught_up
            }

            // 4th subscriber exceeds max=3 -> RATE_LIMITED
            UUID u4 = createTestUser(orgId, Role.OPERATOR);
            String t4 = createWsTicket(u4, orgId, Role.OPERATOR, "Op 4");
            TestWsClient c4 = connectAndAuth(t4);
            c4.nextMessage(3, TimeUnit.SECONDS); // auth_ok
            c4.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
            JsonNode err = c4.nextMessage(3, TimeUnit.SECONDS);
            assertNotNull(err);
            assertEquals("error", err.get("op").asText());
            assertEquals("RATE_LIMITED", err.get("code").asText());
            assertTrue(c4.isOpen());
        } finally {
            handOffWebSocketHandler.setMaxSubscribersPerSession(200);
        }
    }

    @Test
    void sessionLimitUnsubscribeAndDisconnectFreesSlotAndResubscribeDoesNotDoubleCount() throws Exception {
        UUID orgId = createTestOrg();
        UUID sid = createTestSession(orgId, createTestUser(orgId, Role.OPERATOR));

        handOffWebSocketHandler.setMaxSubscribersPerSession(3);
        try {
            List<TestWsClient> clients = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                UUID u = createTestUser(orgId, Role.OPERATOR);
                String t = createWsTicket(u, orgId, Role.OPERATOR, "Op " + i);
                TestWsClient c = connectAndAuth(t);
                c.nextMessage(3, TimeUnit.SECONDS); // auth_ok
                c.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
                assertNotNull(c.nextMessage(3, TimeUnit.SECONDS)); // subscribed
                assertNotNull(c.nextMessage(3, TimeUnit.SECONDS)); // caught_up
                clients.add(c);
            }

            // Client 1 re-subscribes to same session -> does NOT count twice
            clients.get(0).send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
            assertNotNull(clients.get(0).nextMessage(3, TimeUnit.SECONDS)); // subscribed
            assertNotNull(clients.get(0).nextMessage(3, TimeUnit.SECONDS)); // caught_up

            // Client 2 unsubscribes -> frees 1 slot
            clients.get(1).send("{\"op\":\"unsubscribe\",\"sessionId\":\"" + sid + "\"}");

            // Client 4 can now subscribe
            UUID u4 = createTestUser(orgId, Role.OPERATOR);
            String t4 = createWsTicket(u4, orgId, Role.OPERATOR, "Op 4");
            TestWsClient c4 = connectAndAuth(t4);
            c4.nextMessage(3, TimeUnit.SECONDS); // auth_ok
            c4.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
            assertNotNull(c4.nextMessage(3, TimeUnit.SECONDS)); // subscribed
            assertNotNull(c4.nextMessage(3, TimeUnit.SECONDS)); // caught_up

            // Client 3 closes connection -> frees 1 slot
            clients.get(2).close();
            org.testcontainers.shaded.org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> redisStreamListener.getSubscriberCount(sid) == 2);

            // Client 5 can now subscribe
            UUID u5 = createTestUser(orgId, Role.OPERATOR);
            String t5 = createWsTicket(u5, orgId, Role.OPERATOR, "Op 5");
            TestWsClient c5 = connectAndAuth(t5);
            c5.nextMessage(3, TimeUnit.SECONDS); // auth_ok
            c5.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sid + "\",\"fromSeq\":0}");
            assertNotNull(c5.nextMessage(3, TimeUnit.SECONDS)); // subscribed
            assertNotNull(c5.nextMessage(3, TimeUnit.SECONDS)); // caught_up
        } finally {
            handOffWebSocketHandler.setMaxSubscribersPerSession(200);
        }
    }

    @Test
    void slowConsumerBufferOverflowClosesWith4420() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        client.nextMessage(3, TimeUnit.SECONDS); // subscribed
        client.nextMessage(3, TimeUnit.SECONDS); // caught_up

        // Retrieve active subscription and trigger closeSlowConsumer
        SessionSubscription sub = handOffWebSocketHandler.getSubscriptionForSession(sessionId);
        assertNotNull(sub);
        sub.closeSlowConsumer();

        CloseStatus status = client.closeFuture.get(5, TimeUnit.SECONDS);
        assertEquals(4420, status.getCode(), "Slow consumer buffer overflow must close connection with 4420");
    }

    // ==========================================
    // Concurrency & Repetition Tests
    // ==========================================

    @Test
    void twentyRepetitionsOfLiveEventDelivery() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        client.nextMessage(3, TimeUnit.SECONDS); // subscribed
        client.nextMessage(3, TimeUnit.SECONDS); // caught_up

        // 20 repetitions
        for (int i = 1; i <= 20; i++) {
            Event ev = appendEventInTx(sessionId, "AGENT_TEXT", "rep-" + i);
            JsonNode msg = client.nextMessage(3, TimeUnit.SECONDS);
            assertNotNull(msg, "Timed out on rep " + i);
            assertEquals("event", msg.get("op").asText());
            assertEquals(ev.seq(), msg.get("event").get("seq").asLong());
            assertEquals("rep-" + i, msg.get("event").get("payload").get("text").asText());
        }
    }

    @Test
    void fiveConcurrentSubscribersReceiveLiveEventsInOrder() throws Exception {
        UUID orgId = createTestOrg();
        UUID sessionId = createTestSession(orgId, createTestUser(orgId, Role.OPERATOR));

        int clientCount = 5;
        List<TestWsClient> clients = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            UUID userId = createTestUser(orgId, Role.OPERATOR);
            String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "User-" + i);
            TestWsClient client = connectAndAuth(ticket);
            client.nextMessage(3, TimeUnit.SECONDS); // auth_ok
            client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
            client.nextMessage(3, TimeUnit.SECONDS); // subscribed
            client.nextMessage(3, TimeUnit.SECONDS); // caught_up
            clients.add(client);
        }

        // Publish live events
        Event ev1 = appendEventInTx(sessionId, "AGENT_TEXT", "concurrent-msg-1");
        Event ev2 = appendEventInTx(sessionId, "AGENT_TEXT", "concurrent-msg-2");

        for (int i = 0; i < clientCount; i++) {
            TestWsClient c = clients.get(i);
            JsonNode msg1 = c.nextMessage(3, TimeUnit.SECONDS);
            assertNotNull(msg1, "Client " + i + " missed msg1");
            assertEquals("event", msg1.get("op").asText());
            assertEquals(ev1.seq(), msg1.get("event").get("seq").asLong());

            JsonNode msg2 = c.nextMessage(3, TimeUnit.SECONDS);
            assertNotNull(msg2, "Client " + i + " missed msg2");
            assertEquals("event", msg2.get("op").asText());
            assertEquals(ev2.seq(), msg2.get("event").get("seq").asLong());
        }
    }

    // ==========================================
    // Publish Path & Equality Tests (Item 6)
    // ==========================================

    @Test
    void publishPathEventsAppendedBySessionCreationArePublishedToRedis() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);

        ticketRepository.createTicket(new Ticket(
                orgId, "T-101", "Aarav Sharma", "aarav@example.com",
                "Where is my order?", "Need update", "OPEN", null,
                java.time.Instant.now(), java.time.Instant.now()
        ));

        CreateSessionRequest req = new CreateSessionRequest("T-101", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000);
        SessionResponse resp = sessionService.createSession(orgId, userId, "Test Op", req);

        String streamKey = RedisStreamPublisher.getStreamKey(resp.id());
        Long streamSize = redisTemplate.opsForStream().size(streamKey);
        assertNotNull(streamSize);
        assertTrue(streamSize >= 2, "Session creation SESSION_STARTED and CONTROL_TAKEN events must be published to Redis stream");
    }

    @Test
    void publishPathEventsAppendedByAgentRunnerArePublishedToRedis() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);

        orderRepository.createOrder(new Order(
                orgId, "8841", "Aarav Sharma", "aarav@example.com",
                "[{\"sku\":\"SKU-99\",\"name\":\"Headphones\",\"qty\":1,\"price\":4200}]",
                4200, "INR", "DELIVERED", java.time.Instant.now()
        ));
        ticketRepository.createTicket(new Ticket(
                orgId, "T-101", "Aarav Sharma", "aarav@example.com",
                "Where is my order?", "Need update", "OPEN", "8841",
                java.time.Instant.now(), java.time.Instant.now()
        ));

        CreateSessionRequest req = new CreateSessionRequest("T-101", "SCRIPTED", "SIMPLE_LOOKUP", 20, 20000);
        SessionResponse resp = sessionService.createSession(orgId, userId, "Test Op", req);

        // Wait for ScriptedAgent async execution to complete using Awaitility
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            Session s = sessionRepository.findById(orgId, resp.id()).orElse(null);
            return s != null && s.status() == SessionStatus.COMPLETED;
        });

        String streamKey = RedisStreamPublisher.getStreamKey(resp.id());
        Long streamSize = redisTemplate.opsForStream().size(streamKey);
        assertNotNull(streamSize);
        assertEquals(13L, streamSize, "SIMPLE_LOOKUP scenario produces 13 events total published to Redis stream");
    }

    @Test
    void publishPathEventsAppendedByToolExecutionServiceArePublishedToRedis() {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        ticketRepository.createTicket(new Ticket(
                orgId, "T-101", "Customer", "c@example.com",
                "Sub", "Desc", "OPEN", null, java.time.Instant.now(), java.time.Instant.now()
        ));

        toolExecutionService.executeAndRecord(
                orgId,
                sessionId,
                "call-123",
                "add_note",
                Map.of("ticketId", "T-101", "text", "Test tool note")
        );

        String streamKey = RedisStreamPublisher.getStreamKey(sessionId);
        Long streamSize = redisTemplate.opsForStream().size(streamKey);
        assertNotNull(streamSize);
        assertTrue(streamSize >= 1, "TOOL_RESULT event from ToolExecutionService must be published to Redis stream");
    }

    @Test
    void liveEventEqualsReplayedEventIncludingCreatedAt() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        client.nextMessage(3, TimeUnit.SECONDS); // subscribed
        client.nextMessage(3, TimeUnit.SECONDS); // caught_up

        // Append live event
        Event liveEvent = appendEventInTx(sessionId, "AGENT_TEXT", "compare-equality");

        JsonNode liveJson = client.nextMessage(3, TimeUnit.SECONDS);
        assertEquals("event", liveJson.get("op").asText());
        JsonNode liveEvNode = liveJson.get("event");

        // Fetch replayed event directly from database via eventStore
        List<Event> replayedList = eventStore.getEvents(sessionId, 0, 10);
        assertEquals(1, replayedList.size());
        Event replayedEvent = replayedList.get(0);

        // Compare all fields
        assertEquals(replayedEvent.sessionId(), UUID.fromString(liveEvNode.get("sessionId").asText()));
        assertEquals(replayedEvent.seq(), liveEvNode.get("seq").asLong());
        assertEquals(replayedEvent.type(), liveEvNode.get("type").asText());
        assertEquals(replayedEvent.actor().kind().name(), liveEvNode.get("actor").get("kind").asText());
        assertEquals(replayedEvent.payload().get("text"), liveEvNode.get("payload").get("text").asText());

        // Compare created_at timestamp string
        String liveCreatedAtStr = liveEvNode.get("createdAt").asText();
        assertEquals(replayedEvent.createdAt().toString(), liveCreatedAtStr);
    }

    @Test
    void eventsOfOneTransactionArePublishedInSeqOrder() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);

        // Append 3 events within one transaction
        transactionTemplate.execute(status -> {
            eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of("text", "tx-1"));
            eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of("text", "tx-2"));
            eventStore.append(sessionId, "AGENT_TEXT", ActorKind.AGENT, "agent", "Agent", null, Map.of("text", "tx-3"));
            return null;
        });

        // Read records directly from Redis stream
        String streamKey = RedisStreamPublisher.getStreamKey(sessionId);
        var records = redisTemplate.opsForStream().range(streamKey, org.springframework.data.domain.Range.unbounded());
        assertNotNull(records);
        assertEquals(3, records.size());

        assertEquals("1", records.get(0).getValue().get("seq"));
        assertEquals("2", records.get(1).getValue().get("seq"));
        assertEquals("3", records.get(2).getValue().get("seq"));
    }

    // ==========================================
    // Stage B: Reconciliation Job Tests
    // ==========================================

    @Test
    void reconciliationCatchesUpDroppedFinalEvent() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        client.nextMessage(3, TimeUnit.SECONDS); // subscribed
        client.nextMessage(3, TimeUnit.SECONDS); // caught_up

        // Event 1 delivered normally via Redis stream
        Event ev1 = appendEventInTx(sessionId, "AGENT_TEXT", "event 1");
        JsonNode m1 = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(m1, "Event 1 must be delivered");
        assertEquals("event", m1.get("op").asText());
        assertEquals(ev1.seq(), m1.get("event").get("seq").asLong());

        // Configure test drop filter: simulate Redis publishing failure for final event
        redisStreamPublisher.setTestDropFilter(ev -> "SESSION_COMPLETED".equals(ev.type()));

        Event finalEvent = appendEventInTx(sessionId, "SESSION_COMPLETED", "Session finished");
        assertEquals(2L, finalEvent.seq());

        // With reconciliation enabled, the background job catches up within test interval (100ms)
        JsonNode m2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(m2, "Dropped final event must be delivered via reconciliation");
        assertEquals("event", m2.get("op").asText());
        assertEquals("SESSION_COMPLETED", m2.get("event").get("type").asText());
        assertEquals(finalEvent.seq(), m2.get("event").get("seq").asLong());
    }

    @Test
    void upToDateSubscriptionReceivesNoDuplicatesDuringReconciliation() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        client.nextMessage(3, TimeUnit.SECONDS); // subscribed
        client.nextMessage(3, TimeUnit.SECONDS); // caught_up

        Event ev1 = appendEventInTx(sessionId, "AGENT_TEXT", "event 1");
        JsonNode m1 = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(m1);
        assertEquals(ev1.seq(), m1.get("event").get("seq").asLong());

        // Explicitly trigger reconciliation job while subscription is up to date
        reconciliationService.runReconciliation();

        // Verify no duplicate message arrived using timeout
        JsonNode duplicate = client.nextMessage(250, TimeUnit.MILLISECONDS);
        assertNull(duplicate, "Subscription that is up to date must receive no duplicates");
    }

    // ==========================================
    // Hardening Tests (Stage B Piece 3)
    // ==========================================

    @Test
    void malformedJsonUnknownOpAndCommandReturnValidationFailedAndKeepConnectionOpen() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        // 1. Malformed JSON
        client.send("{not-valid-json");
        JsonNode err1 = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(err1);
        assertEquals("error", err1.get("op").asText());
        assertEquals("VALIDATION_FAILED", err1.get("code").asText());
        assertTrue(client.isOpen());

        // 2. Unknown op
        client.send("{\"op\":\"fly_to_moon\"}");
        JsonNode err2 = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(err2);
        assertEquals("error", err2.get("op").asText());
        assertEquals("VALIDATION_FAILED", err2.get("code").asText());
        assertTrue(client.isOpen());

        // 3. Command op (Slice 5 not yet enabled)
        client.send("{\"op\":\"command\",\"id\":\"c-99\",\"type\":\"STEER\"}");
        JsonNode err3 = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(err3);
        assertEquals("error", err3.get("op").asText());
        assertEquals("VALIDATION_FAILED", err3.get("code").asText());
        assertEquals("c-99", err3.get("id").asText());
        assertTrue(client.isOpen());

        // Prove connection is still fully functional: subscribe and get reply
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(subResp);
        assertEquals("subscribed", subResp.get("op").asText());
        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(caughtUp);
        assertEquals("caught_up", caughtUp.get("op").asText());
        assertTrue(client.isOpen());
    }

    @Test
    void queueOverflowMarksBehindAndCatchesUpFromPostgresWithoutLoss() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // subscribed
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // caught_up

        SessionSubscription sub = handOffWebSocketHandler.getSubscriptionForSession(sessionId);
        assertNotNull(sub);

        // Force queue capacity to 2 via reflection so it overflows quickly
        java.lang.reflect.Field qField = SessionSubscription.class.getDeclaredField("liveQueue");
        qField.setAccessible(true);
        BlockingQueue<Event> smallQueue = new LinkedBlockingQueue<>(2);
        qField.set(sub, smallQueue);

        // Append 5 events to PostgreSQL and Redis
        for (int i = 1; i <= 5; i++) {
            appendEventInTx(sessionId, "AGENT_TEXT", "msg-" + i);
        }

        // The client must receive all 5 events gapless and in order
        for (int i = 1; i <= 5; i++) {
            JsonNode msg = client.nextMessage(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Expected event seq " + i);
            assertEquals("event", msg.get("op").asText());
            assertEquals(i, msg.get("event").get("seq").asLong());
        }
    }

    @Test
    void redisReaderRecoversFromErrorAndDeliveryResumes() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // subscribed
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // caught_up

        // Disable reconciliation so delivery can ONLY come from the recovered Redis reader
        reconciliationService.setEnabled(false);
        try {
            // Trigger Redis read error via test hook; reader catches it, backs off, reconnects and restarts
            redisStreamListener.setTestReadException(new org.springframework.data.redis.RedisSystemException(
                    "Simulated Redis read failure", new RuntimeException("connection reset")));

            long start = System.currentTimeMillis();

            // Append an event after Redis connection error; reader must recover and deliver it
            appendEventInTx(sessionId, "AGENT_TEXT", "after-error");

            // Event must arrive via the Redis reader, well under the 2-second reconciliation interval
            JsonNode eventMsg = client.nextMessage(2, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertNotNull(eventMsg, "Delivery must resume after reader restart");
            assertEquals("event", eventMsg.get("op").asText());
            assertEquals(1L, eventMsg.get("event").get("seq").asLong());
            assertTrue(elapsed < 1500, "Event must arrive via Redis reader well under 2s (elapsed: " + elapsed + "ms)");
        } finally {
            reconciliationService.setEnabled(true);
            redisStreamListener.setTestReadException(null);
        }
    }

    @Test
    void subscribingWhileRedisReaderIsBlockedWorksImmediately() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        // The reader is currently blocked in XREAD block 500ms since no events exist
        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        long start = System.currentTimeMillis();
        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");

        JsonNode subResp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(subResp);
        assertEquals("subscribed", subResp.get("op").asText());

        JsonNode caughtUp = client.nextMessage(3, TimeUnit.SECONDS);
        assertNotNull(caughtUp);
        assertEquals("caught_up", caughtUp.get("op").asText());

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 2000, "Subscribing must not be delayed by blocked Redis reader");
    }

    @Test
    void redisReaderStopsReadingStreamWhenZeroSubscribers() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        String streamKey = RedisStreamPublisher.getStreamKey(sessionId);

        java.lang.reflect.Field offsetsField = RedisStreamListener.class.getDeclaredField("streamOffsets");
        offsetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> streamOffsets =
                (ConcurrentHashMap<String, String>) offsetsField.get(redisStreamListener);

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // subscribed
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // caught_up

        // Active subscriber -> stream key is tracked
        assertTrue(streamOffsets.containsKey(streamKey));

        // Unsubscribe -> stream key removed
        client.send("{\"op\":\"unsubscribe\",\"sessionId\":\"" + sessionId + "\"}");
        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .until(() -> !streamOffsets.containsKey(streamKey));
    }

    @Test
    void connectionCleanupRemovesFromConnectionsCancelsAuthTimeoutAndUnregistersSubscriptions() throws Exception {
        UUID orgId = createTestOrg();
        UUID userId = createTestUser(orgId, Role.OPERATOR);
        UUID sessionId = createTestSession(orgId, userId);
        String ticket = createWsTicket(userId, orgId, Role.OPERATOR, "Alice");

        TestWsClient client = connectAndAuth(ticket);
        client.nextMessage(3, TimeUnit.SECONDS); // auth_ok

        client.send("{\"op\":\"subscribe\",\"sessionId\":\"" + sessionId + "\",\"fromSeq\":0}");
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // subscribed
        assertNotNull(client.nextMessage(3, TimeUnit.SECONDS)); // caught_up

        // Subscriber registered in RedisStreamListener
        assertEquals(1, redisStreamListener.getSubscriberCount(sessionId));

        java.lang.reflect.Field connMapField = HandOffWebSocketHandler.class.getDeclaredField("connections");
        connMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, ?> connections =
                (ConcurrentHashMap<String, ?>) connMapField.get(handOffWebSocketHandler);

        assertFalse(connections.isEmpty());
        String serverConnId = connections.keySet().iterator().next();
        assertTrue(connections.containsKey(serverConnId));

        // Close connection
        client.close();

        // Verify cleanup via afterConnectionClosed
        org.testcontainers.shaded.org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .until(() -> !connections.containsKey(serverConnId));

        assertEquals(0, redisStreamListener.getSubscriberCount(sessionId));
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private TestWsClient connectClient() throws Exception {
        TestWsClient client = new TestWsClient(port);
        openClients.add(client);
        return client;
    }

    private TestWsClient connectAndAuth(String ticket) throws Exception {
        TestWsClient client = connectClient();
        client.send("{\"op\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        return client;
    }

    private String createWsTicket(UUID userId, UUID orgId, Role role, String name) {
        UserPrincipal principal = new UserPrincipal(userId, orgId, role, name);
        return wsTicketService.createTicket(principal).ticket();
    }

    private Event appendEventInTx(UUID sessionId, String type, String text) {
        return transactionTemplate.execute(status ->
                eventStore.append(
                        sessionId,
                        type,
                        ActorKind.AGENT,
                        "agent",
                        "Agent",
                        null,
                        Map.of("text", text)
                )
        );
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

    private UUID createTestSession(UUID orgId, UUID userId) {
        UUID sessionId = UUID.randomUUID();
        String ticketId = "T-" + sessionId;
        ticketRepository.createTicket(new Ticket(
                orgId, ticketId, "Test Customer", "cust@example.com",
                "Sub", "Desc", "OPEN", null, java.time.Instant.now(), java.time.Instant.now()
        ));
        jdbc.update("""
            INSERT INTO sessions (id, organization_id, ticket_id, agent_type, status,
                                  last_seq, step_budget, token_budget, created_by)
            VALUES (?, ?, ?, 'SCRIPTED', 'RUNNING', 0, 20, 20000, ?)
            """, sessionId, orgId, ticketId, userId);
        return sessionId;
    }

    private String serializeEvent(Event event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class TestWsClient {
        private final WebSocketSession session;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closeFuture = new CompletableFuture<>();
        private final ObjectMapper mapper = new ObjectMapper();

        public TestWsClient(int port) throws Exception {
            this(port, null);
        }

        public TestWsClient(int port, WebSocketHttpHeaders customHeaders) throws Exception {
            jakarta.websocket.WebSocketContainer container = jakarta.websocket.ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(512 * 1024);
            container.setDefaultMaxBinaryMessageBufferSize(512 * 1024);
            StandardWebSocketClient client = new StandardWebSocketClient(container);
            WebSocketHttpHeaders headers = customHeaders != null ? customHeaders : new WebSocketHttpHeaders();
            this.session = client.execute(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                    messages.offer(message.getPayload());
                }

                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                    closeFuture.complete(status);
                }
            }, headers, URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
        }

        public void send(String payload) throws IOException {
            session.sendMessage(new TextMessage(payload));
        }

        public JsonNode nextMessage(long timeout, TimeUnit unit) throws Exception {
            String raw = messages.poll(timeout, unit);
            if (raw == null) {
                return null;
            }
            return mapper.readTree(raw);
        }

        public String getId() {
            return session != null ? session.getId() : null;
        }

        public boolean isOpen() {
            return session != null && session.isOpen();
        }

        public void close() {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {}
        }
    }
}

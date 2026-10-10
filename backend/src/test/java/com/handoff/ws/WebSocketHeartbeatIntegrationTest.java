package com.handoff.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.AbstractIntegrationTest;
import com.handoff.auth.Role;
import com.handoff.auth.UserPrincipal;
import com.handoff.auth.WsTicketService;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@TestPropertySource(properties = {
        "handoff.ws.ping-interval-ms=100",
        "handoff.ws.pong-timeout-ms=250",
        "handoff.ws.heartbeat-tick-ms=25"
})
public class WebSocketHeartbeatIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    private final List<TestHeartbeatClient> openClients = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        for (TestHeartbeatClient client : openClients) {
            client.close();
        }
        openClients.clear();
    }

    @Test
    void heartbeatNoPongClosesConnection() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ticket = wsTicketService.createTicket(new UserPrincipal(userId, orgId, Role.OPERATOR, "Alice")).ticket();

        TestHeartbeatClient client = new TestHeartbeatClient(port);
        openClients.add(client);

        client.send("{\"op\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        JsonNode authOk = client.nextMessage(2, TimeUnit.SECONDS);
        assertNotNull(authOk);
        assertEquals("auth_ok", authOk.get("op").asText());

        long pingSentApprox = System.currentTimeMillis();
        JsonNode ping = client.nextMessage(2, TimeUnit.SECONDS);
        assertNotNull(ping, "Server must send ping");
        assertEquals("ping", ping.get("op").asText());

        // Client does not reply with pong -> server closes connection with 4409
        CloseStatus status = client.closeFuture.get(2, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - pingSentApprox;

        assertEquals(4409, status.getCode(), "Missed pong must close with 4409");
        assertTrue(elapsed < 2000, "Close must happen in under 2 seconds, elapsed: " + elapsed + "ms");
    }

    @Test
    void heartbeatPongKeepsConnectionOpen() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ticket = wsTicketService.createTicket(new UserPrincipal(userId, orgId, Role.OPERATOR, "Bob")).ticket();

        TestHeartbeatClient client = new TestHeartbeatClient(port);
        openClients.add(client);

        client.send("{\"op\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        JsonNode authOk = client.nextMessage(2, TimeUnit.SECONDS);
        assertNotNull(authOk);
        assertEquals("auth_ok", authOk.get("op").asText());

        // Answer at least 3 pings
        CountDownLatch latch = new CountDownLatch(3);
        AtomicBoolean running = new AtomicBoolean(true);

        Thread responder = new Thread(() -> {
            try {
                while (running.get()) {
                    JsonNode msg = client.nextMessage(500, TimeUnit.MILLISECONDS);
                    if (msg != null && "ping".equals(msg.path("op").asText())) {
                        client.send("{\"op\":\"pong\"}");
                        latch.countDown();
                    }
                }
            } catch (Exception ignored) {}
        });
        responder.setDaemon(true);
        responder.start();

        try {
            boolean received3 = latch.await(2, TimeUnit.SECONDS);
            assertTrue(received3, "Should receive and answer at least 3 pings");
            assertTrue(client.isOpen(), "Connection must stay open when replying to pings");
            assertFalse(client.closeFuture.isDone(), "Connection must not be closed");
        } finally {
            running.set(false);
        }
    }

    public static class TestHeartbeatClient {
        private final WebSocketSession session;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closeFuture = new CompletableFuture<>();
        private final ObjectMapper mapper = new ObjectMapper();

        public TestHeartbeatClient(int port) throws Exception {
            jakarta.websocket.WebSocketContainer container = jakarta.websocket.ContainerProvider.getWebSocketContainer();
            StandardWebSocketClient client = new StandardWebSocketClient(container);
            this.session = client.execute(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                    messages.offer(message.getPayload());
                }

                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                    closeFuture.complete(status);
                }
            }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
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

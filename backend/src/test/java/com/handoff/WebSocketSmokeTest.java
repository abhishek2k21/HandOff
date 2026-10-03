package com.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Verifies that the WebSocket endpoint at /ws is reachable
 * and responds with the echo ping message.
 *
 * How it works:
 * - StandardWebSocketClient is Spring's built-in WS client.
 * - We connect to ws://localhost:{random-port}/ws.
 * - We send any text message.
 * - The EchoWebSocketHandler replies with {"op":"ping"}.
 * - We verify we received it.
 */
class WebSocketSmokeTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void webSocketEchoWorks() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();

        // A CompletableFuture that completes when we receive a message.
        CompletableFuture<String> received = new CompletableFuture<>();

        WebSocketSession session = client.execute(
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session,
                                                     TextMessage message) {
                        received.complete(message.getPayload());
                    }
                },
                null,   // no extra headers
                URI.create("ws://localhost:" + port + "/ws")
        ).get(5, TimeUnit.SECONDS);

        // Send any message — the echo handler always replies with ping.
        session.sendMessage(new TextMessage("{\"hello\":true}"));

        String response = received.get(5, TimeUnit.SECONDS);
        assertEquals("{\"op\":\"ping\"}", response);

        session.close();
    }
}

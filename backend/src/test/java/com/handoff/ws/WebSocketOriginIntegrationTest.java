package com.handoff.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handoff.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "handoff.ws.allowed-origins=http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173"
})
public class WebSocketOriginIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void originAllowedAccepted() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {});

        WebSocket ws = wsFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(ws);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void originDisallowedRejectedWith403() {
        HttpClient httpClient = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .header("Origin", "http://evil.example.com")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {});

        ExecutionException ex = assertThrows(ExecutionException.class, () -> wsFuture.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof WebSocketHandshakeException,
                "Expected cause to be WebSocketHandshakeException, was " + ex.getCause());
        WebSocketHandshakeException handshakeEx = (WebSocketHandshakeException) ex.getCause();
        assertEquals(403, handshakeEx.getResponse().statusCode());
    }

    @Test
    void originMissingAccepted() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {});

        WebSocket ws = wsFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(ws);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
}

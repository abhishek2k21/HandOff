package com.handoff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers WebSocket handlers.
 *
 * We use plain Spring WebSocket (not STOMP) because docs/events.md
 * defines its own JSON protocol with ops like "auth", "subscribe",
 * "command", "event", "ping", "pong", etc.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(echoWebSocketHandler(), "/ws")
                .setAllowedOrigins("http://localhost:5173");
    }

    @Bean
    public EchoWebSocketHandler echoWebSocketHandler() {
        return new EchoWebSocketHandler();
    }
}

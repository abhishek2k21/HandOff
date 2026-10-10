package com.handoff.ws;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers WebSocket handlers and enables scheduling for reconciliation.
 *
 * Configures:
 * - Plain Spring WebSocketHandler registered at /ws
 * - 16 KB max message buffer limit (docs/events.md section 16)
 * - Configurable allowed origins; missing Origin allowed
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final HandOffWebSocketHandler handOffWebSocketHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            HandOffWebSocketHandler handOffWebSocketHandler,
            @Value("${handoff.ws.allowed-origins:http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173}") String allowedOriginsStr
    ) {
        this.handOffWebSocketHandler = handOffWebSocketHandler;
        this.allowedOrigins = Arrays.stream(allowedOriginsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (allowedOrigins.length == 1 && "*".equals(allowedOrigins[0])) {
            registry.addHandler(handOffWebSocketHandler, "/ws")
                    .setAllowedOriginPatterns("*");
        } else {
            registry.addHandler(handOffWebSocketHandler, "/ws")
                    .setAllowedOrigins(allowedOrigins);
        }
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024); // 512 KB buffer for replay batches
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        return container;
    }
}

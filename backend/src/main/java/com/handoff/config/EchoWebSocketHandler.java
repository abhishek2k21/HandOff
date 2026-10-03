package com.handoff.config;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * TEMPORARY: This echo handler will be replaced in slice 3 with the real
 * event-streaming protocol defined in docs/events.md section 9.
 *
 * For now it just replies with {"op":"ping"} to any message,
 * proving the WebSocket plumbing works end-to-end.
 */
public class EchoWebSocketHandler extends TextWebSocketHandler {

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.sendMessage(new TextMessage("{\"op\":\"ping\"}"));
    }
}

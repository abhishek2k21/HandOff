package com.handoff.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.auth.Role;
import com.handoff.auth.WsTicketService.WsTicketPayload;
import com.handoff.events.EventStore;
import com.handoff.session.SessionRepository;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionSubscriptionTest {

    @Test
    void midReplaySubscriptionIsNotCaughtUp() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        WsTicketPayload user = new WsTicketPayload(userId, orgId, Role.OPERATOR, "Alice");

        WebSocketSession rawSession = mock(WebSocketSession.class);
        when(rawSession.isOpen()).thenReturn(true);
        ConcurrentWebSocketSessionDecorator sessionDecorator =
                new ConcurrentWebSocketSessionDecorator(rawSession, 5000, 512 * 1024);

        EventStore eventStore = mock(EventStore.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Executor executor = mock(Executor.class);
        RedisStreamListener streamListener = mock(RedisStreamListener.class);

        SessionSubscription sub = new SessionSubscription(
                sessionId,
                user,
                0L,
                1L,
                sessionDecorator,
                eventStore,
                sessionRepository,
                objectMapper,
                executor,
                streamListener,
                () -> 1L
        );

        assertEquals(SessionSubscription.State.REPLAYING, sub.getState());
        assertEquals(0L, sub.getLastSentSeq());

        // Invoke catchUpIfBehind while in REPLAYING state
        sub.catchUpIfBehind(100L);

        // Executor must NOT be called for task submission
        verify(executor, never()).execute(any());
        verifyNoInteractions(eventStore);
        assertEquals(0L, sub.getLastSentSeq());
    }
}

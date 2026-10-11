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

    @Test
    void unregisterTwiceDoesNotCorruptSubscriberCount() {
        org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory =
                mock(org.springframework.data.redis.connection.RedisConnectionFactory.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RedisStreamListener listener = new RedisStreamListener(connectionFactory, objectMapper);

        UUID sessionId = UUID.randomUUID();
        SessionSubscription sub1 = mock(SessionSubscription.class);
        when(sub1.getSessionId()).thenReturn(sessionId);

        SessionSubscription sub2 = mock(SessionSubscription.class);
        when(sub2.getSessionId()).thenReturn(sessionId);

        // Register both subscriptions
        listener.register(sub1);
        listener.register(sub2);
        assertEquals(2, listener.getSubscriberCount(sessionId));

        // Unregister sub1 first time
        listener.unregister(sub1);
        assertEquals(1, listener.getSubscriberCount(sessionId));

        // Unregister sub1 second time (duplicate call) -> count remains 1, not decremented or corrupted
        listener.unregister(sub1);
        assertEquals(1, listener.getSubscriberCount(sessionId));

        // Unregister sub2
        listener.unregister(sub2);
        assertEquals(0, listener.getSubscriberCount(sessionId));

        // Unregister sub2 second time -> count remains 0
        listener.unregister(sub2);
        assertEquals(0, listener.getSubscriberCount(sessionId));
    }
}

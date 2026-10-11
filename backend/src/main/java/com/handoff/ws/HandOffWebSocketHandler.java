package com.handoff.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.auth.Role;
import com.handoff.auth.WsTicketService;
import com.handoff.auth.WsTicketService.WsTicketPayload;
import com.handoff.common.UnauthenticatedException;
import com.handoff.events.EventStore;
import com.handoff.session.Session;
import com.handoff.session.SessionRepository;
import com.handoff.ws.protocol.WsMessage;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Raw Spring WebSocketHandler implementing docs/events.md section 9.
 * Handles:
 * - Ticket-based authentication (4408 timeout, 4401 invalid/reused/missing)
 * - Safe concurrent message delivery via ConcurrentWebSocketSessionDecorator
 * - Subscribe, atomic re-subscribe, and unsubscribe
 * - Strictly prevents logging sensitive payloads or tickets
 */
@Component
public class HandOffWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(HandOffWebSocketHandler.class);

    public static final CloseStatus STATUS_AUTH_TIMEOUT = new CloseStatus(4408, "Authentication timeout");
    public static final CloseStatus STATUS_UNAUTHENTICATED = new CloseStatus(4401, "Unauthenticated");
    public static final CloseStatus STATUS_MESSAGE_TOO_BIG = new CloseStatus(1009, "Message too big");
    public static final CloseStatus STATUS_SLOW_CONSUMER = new CloseStatus(4420, "Slow consumer buffer overflow");
    public static final CloseStatus STATUS_HEARTBEAT_TIMEOUT = new CloseStatus(4409, "Heartbeat timeout");
    public static final CloseStatus STATUS_INTERNAL_ERROR = new CloseStatus(4500, "Internal server error");

    private final WsTicketService wsTicketService;
    private final EventStore eventStore;
    private final SessionRepository sessionRepository;
    private final RedisStreamListener redisStreamListener;
    private final ObjectMapper objectMapper;
    private volatile long authTimeoutSeconds;
    private volatile long pingIntervalMs;
    private volatile long pongTimeoutMs;
    private volatile long heartbeatTickMs;
    private volatile int maxSubscriptionsPerConnection;
    private volatile int maxSubscribersPerSession;
    private volatile int sendTimeLimitMs;
    private volatile int sendBufferSizeLimit;
    private volatile int bufferMaxCap;

    public void setAuthTimeoutSeconds(long authTimeoutSeconds) {
        this.authTimeoutSeconds = authTimeoutSeconds;
    }

    public void setMaxSubscriptionsPerConnection(int max) {
        this.maxSubscriptionsPerConnection = max;
    }

    public void setMaxSubscribersPerSession(int max) {
        this.maxSubscribersPerSession = max;
    }

    public void setSendTimeLimitMs(int ms) {
        this.sendTimeLimitMs = ms;
    }

    public void setSendBufferSizeLimit(int bytes) {
        this.sendBufferSizeLimit = bytes;
    }

    public void setBufferMaxCap(int bytes) {
        this.bufferMaxCap = bytes;
    }

    public int getSendTimeLimitMs() {
        return this.sendTimeLimitMs;
    }

    public int getSendBufferSizeLimit() {
        return this.sendBufferSizeLimit;
    }

    public int getBufferMaxCap() {
        return this.bufferMaxCap;
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Executor subscriptionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledFuture<?> heartbeatFuture;

    // Connection tracking
    private final ConcurrentHashMap<String, ConnectionState> connections = new ConcurrentHashMap<>();

    // Test hooks
    Runnable testHookAfterRegistrationBeforeReaderActivation = null;
    Runnable testHookBeforeHistoryRead = null;
    Runnable testHookBeforeBufferFlush = null;
    java.util.function.Function<org.springframework.web.socket.WebSocketSession, org.springframework.web.socket.WebSocketSession> testHookRawSessionDecorator = null;

    public void setTestHookAfterRegistrationBeforeReaderActivation(Runnable r) {
        this.testHookAfterRegistrationBeforeReaderActivation = r;
    }

    public void setTestHookBeforeHistoryRead(Runnable r) {
        this.testHookBeforeHistoryRead = r;
    }

    public void setTestHookBeforeBufferFlush(Runnable r) {
        this.testHookBeforeBufferFlush = r;
    }

    public void setTestHookRawSessionDecorator(java.util.function.Function<org.springframework.web.socket.WebSocketSession, org.springframework.web.socket.WebSocketSession> fn) {
        this.testHookRawSessionDecorator = fn;
    }

    private static class ConnectionState {
        final ConcurrentWebSocketSessionDecorator session;
        final ScheduledFuture<?> authTimeoutTask;
        volatile WsTicketPayload user;
        volatile long lastPingAt = 0;
        volatile long pendingPongSince = 0;
        final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean(false);
        final ConcurrentHashMap<UUID, SessionSubscription> subscriptions = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, AtomicLong> sessionGenerations = new ConcurrentHashMap<>();
        final AtomicLong generationCounter = new AtomicLong(0);

        ConnectionState(ConcurrentWebSocketSessionDecorator session, ScheduledFuture<?> authTimeoutTask) {
            this.session = session;
            this.authTimeoutTask = authTimeoutTask;
        }
    }

    public HandOffWebSocketHandler(
            WsTicketService wsTicketService,
            EventStore eventStore,
            SessionRepository sessionRepository,
            RedisStreamListener redisStreamListener,
            ObjectMapper objectMapper,
            @Value("${handoff.ws.auth-timeout-seconds:5}") long authTimeoutSeconds,
            @Value("${handoff.ws.ping-interval-ms:15000}") long pingIntervalMs,
            @Value("${handoff.ws.pong-timeout-ms:10000}") long pongTimeoutMs,
            @Value("${handoff.ws.heartbeat-tick-ms:1000}") long heartbeatTickMs,
            @Value("${handoff.ws.max-subscriptions-per-connection:20}") int maxSubscriptionsPerConnection,
            @Value("${handoff.ws.max-subscribers-per-session:200}") int maxSubscribersPerSession,
            @Value("${handoff.ws.send-time-limit-ms:5000}") int sendTimeLimitMs,
            @Value("${handoff.ws.send-buffer-size-limit:1048576}") int sendBufferSizeLimit,
            @Value("${handoff.ws.buffer-max-cap:524288}") int bufferMaxCap
    ) {
        this.wsTicketService = wsTicketService;
        this.eventStore = eventStore;
        this.sessionRepository = sessionRepository;
        this.redisStreamListener = redisStreamListener;
        this.objectMapper = objectMapper;
        this.authTimeoutSeconds = authTimeoutSeconds;
        this.pingIntervalMs = pingIntervalMs;
        this.pongTimeoutMs = pongTimeoutMs;
        this.heartbeatTickMs = heartbeatTickMs;
        this.maxSubscriptionsPerConnection = maxSubscriptionsPerConnection;
        this.maxSubscribersPerSession = maxSubscribersPerSession;
        this.sendTimeLimitMs = sendTimeLimitMs;
        this.sendBufferSizeLimit = sendBufferSizeLimit;
        this.bufferMaxCap = bufferMaxCap;

        this.heartbeatFuture = this.scheduler.scheduleWithFixedDelay(
                this::heartbeatTick,
                heartbeatTickMs,
                heartbeatTickMs,
                TimeUnit.MILLISECONDS
        );
    }

    @jakarta.annotation.PreDestroy
    public void destroy() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        scheduler.shutdown();
    }

    private void heartbeatTick() {
        long now = System.currentTimeMillis();
        for (ConnectionState state : connections.values()) {
            if (state.user == null || state.closing.get()) {
                continue;
            }
            if (state.pendingPongSince != 0) {
                if (now - state.pendingPongSince > pongTimeoutMs) {
                    if (state.closing.compareAndSet(false, true)) {
                        subscriptionExecutor.execute(() -> {
                            try {
                                state.session.close(STATUS_HEARTBEAT_TIMEOUT);
                            } catch (IOException ignored) {}
                        });
                    }
                }
            } else if (now - state.lastPingAt >= pingIntervalMs) {
                state.lastPingAt = now;
                state.pendingPongSince = now;
                subscriptionExecutor.execute(() -> {
                    try {
                        sendMessage(state.session, new WsMessage.PingMessage());
                    } catch (SessionLimitExceededException ex) {
                        log.warn("Slow consumer on ping for connId={}. Closed with 4420.", state.session.getId());
                    } catch (Exception ex) {
                        if (state.closing.compareAndSet(false, true)) {
                            try {
                                state.session.close(CloseStatus.SERVER_ERROR);
                            } catch (IOException ignored) {}
                        }
                    }
                });
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) {
        // Wrap in thread-safe decorator: sendTimeLimitMs send limit, sendBufferSizeLimit buffer cap
        WebSocketSession delegateSession = testHookRawSessionDecorator != null
                ? testHookRawSessionDecorator.apply(rawSession)
                : rawSession;
        ConcurrentWebSocketSessionDecorator session =
                new ConcurrentWebSocketSessionDecorator(delegateSession, sendTimeLimitMs, sendBufferSizeLimit);

        String connId = session.getId();
        log.info("WebSocket connected: connId={}", connId);

        // Schedule auth timeout (close 4408 after 5 seconds if not authenticated)
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            ConnectionState state = connections.get(connId);
            if (state != null && state.user == null) {
                log.warn("Auth timeout reached for connId={}. Closing 4408.", connId);
                try {
                    session.close(STATUS_AUTH_TIMEOUT);
                } catch (IOException ignored) {}
            }
        }, authTimeoutSeconds, TimeUnit.SECONDS);

        connections.put(connId, new ConnectionState(session, timeoutTask));
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) throws Exception {
        String connId = rawSession.getId();
        ConnectionState state = connections.get(connId);
        if (state == null) {
            return;
        }

        // Limit: 16 KB message size limit (docs/events.md section 16)
        if (message.getPayloadLength() > 16 * 1024) {
            log.warn("Incoming message exceeds 16 KB ({} bytes) for connId={}. Closing 1009.",
                    message.getPayloadLength(), connId);
            rawSession.close(STATUS_MESSAGE_TOO_BIG);
            return;
        }

        String payload = message.getPayload();
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            sendError(state.session, null, "VALIDATION_FAILED", "Malformed JSON", null);
            return;
        }

        String op = root.has("op") ? root.get("op").asText() : null;
        if (op == null) {
            sendError(state.session, null, "VALIDATION_FAILED", "Missing op field", null);
            return;
        }

        // 1. Enforce Authentication
        if (state.user == null) {
            if (!"auth".equals(op)) {
                log.warn("Non-auth first message received for connId={}. Closing 4401.", connId);
                state.session.close(STATUS_UNAUTHENTICATED);
                return;
            }

            handleAuth(state, root);
            return;
        }

        // 2. Authenticated Operations
        try {
            switch (op) {
                case "subscribe" -> handleSubscribe(state, root);
                case "unsubscribe" -> handleUnsubscribe(state, root);
                case "pong" -> handlePong(state);
                case "command" -> handleCommand(state, root);
                default -> sendError(state.session, null, "VALIDATION_FAILED", "Unknown op: " + op, null);
            }
        } catch (SessionLimitExceededException ex) {
            log.warn("Session limit exceeded in handleTextMessage for connId={}. Closed with 4420.", connId);
        }
    }

    private void handleAuth(ConnectionState state, JsonNode root) throws IOException {
        String ticket = root.has("ticket") ? root.get("ticket").asText() : null;
        if (ticket == null || ticket.isBlank()) {
            state.session.close(STATUS_UNAUTHENTICATED);
            return;
        }

        WsTicketPayload payload;
        try {
            payload = wsTicketService.consumeTicket(ticket);
        } catch (UnauthenticatedException ex) {
            log.warn("Invalid or expired ticket for connId={}. Closing 4401.", state.session.getId());
            state.session.close(STATUS_UNAUTHENTICATED);
            return;
        } catch (Exception ex) {
            log.error("Error consuming ticket for connId={}", state.session.getId(), ex);
            state.session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // Cancel auth timeout
        if (state.authTimeoutTask != null) {
            state.authTimeoutTask.cancel(false);
        }

        state.user = payload;
        long now = System.currentTimeMillis();
        state.lastPingAt = now;
        state.pendingPongSince = 0;
        log.info("WebSocket authenticated: connId={}, userId={}, role={}", state.session.getId(), payload.userId(), payload.role());

        WsMessage.AuthOkUser userDto = new WsMessage.AuthOkUser(payload.userId(), payload.role().name(), payload.name());
        sendMessage(state.session, new WsMessage.AuthOkResponse(userDto));
    }

    private void handleSubscribe(ConnectionState state, JsonNode root) throws IOException {
        String sessionStr = root.has("sessionId") ? root.get("sessionId").asText() : null;
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionStr);
        } catch (Exception ex) {
            sendError(state.session, null, "VALIDATION_FAILED", "Invalid sessionId format", null);
            return;
        }

        long fromSeq = root.has("fromSeq") ? root.get("fromSeq").asLong() : 0L;

        // Verify session exists and belongs to user's organization (Section 8.2 & 9.4)
        Session session = sessionRepository.findById(state.user.organizationId(), sessionId).orElse(null);
        if (session == null) {
            sendError(state.session, null, "UNKNOWN_SESSION",
                    "Session does not exist or belongs to another organization", null);
            return;
        }

        // Verify sequence
        if (fromSeq > session.lastSeq()) {
            sendError(state.session, null, "INVALID_SEQUENCE",
                    "fromSeq is greater than session's lastSeq", null);
            return;
        }

        // Verify max subscriptions per connection (docs/events.md section 16: max 20)
        if (!state.subscriptions.containsKey(sessionId) && state.subscriptions.size() >= maxSubscriptionsPerConnection) {
            sendError(state.session, null, "VALIDATION_FAILED",
                    "Maximum subscriptions per connection (" + maxSubscriptionsPerConnection + ") reached", null);
            return;
        }

        // Atomic re-subscribe: replace any existing subscription for this session on this connection
        long nextGen = state.generationCounter.incrementAndGet();
        state.sessionGenerations.put(sessionId, new AtomicLong(nextGen));
        SessionSubscription newSub = new SessionSubscription(
                sessionId,
                state.user,
                fromSeq,
                nextGen,
                state.session,
                eventStore,
                sessionRepository,
                objectMapper,
                subscriptionExecutor,
                redisStreamListener,
                () -> {
                    AtomicLong currentGen = state.sessionGenerations.get(sessionId);
                    return currentGen != null ? currentGen.get() : 0L;
                }
        );

        // Apply test hooks
        newSub.testHookAfterRegistrationBeforeReaderActivation = this.testHookAfterRegistrationBeforeReaderActivation;
        newSub.testHookBeforeHistoryRead = this.testHookBeforeHistoryRead;
        newSub.testHookBeforeBufferFlush = this.testHookBeforeBufferFlush;
        newSub.setBufferMaxCap(this.bufferMaxCap);

        SessionSubscription oldSub = state.subscriptions.get(sessionId);
        boolean registered = redisStreamListener.tryRegister(newSub, oldSub, maxSubscribersPerSession);
        if (!registered) {
            sendError(state.session, null, "RATE_LIMITED",
                    "Maximum subscribers per session (" + maxSubscribersPerSession + ") reached", null);
            return;
        }

        state.subscriptions.put(sessionId, newSub);
        if (oldSub != null) {
            oldSub.cancel();
        }

        if (testHookAfterRegistrationBeforeReaderActivation != null) {
            testHookAfterRegistrationBeforeReaderActivation.run();
        }

        newSub.start();
    }

    private void handleUnsubscribe(ConnectionState state, JsonNode root) {
        String sessionStr = root.has("sessionId") ? root.get("sessionId").asText() : null;
        if (sessionStr == null) return;
        try {
            UUID sessionId = UUID.fromString(sessionStr);
            SessionSubscription sub = state.subscriptions.remove(sessionId);
            if (sub != null) {
                sub.cancel();
                redisStreamListener.unregister(sub);
            }
        } catch (Exception ignored) {}
    }

    private void handlePong(ConnectionState state) {
        state.pendingPongSince = 0;
    }

    private void handleCommand(ConnectionState state, JsonNode root) throws IOException {
        String id = root.has("id") ? root.get("id").asText() : null;
        sendError(state.session, id, "VALIDATION_FAILED", "Commands are enabled in Slice 5", null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        String connId = rawSession.getId();
        ConnectionState state = connections.remove(connId);
        if (state != null) {
            if (state.authTimeoutTask != null) {
                state.authTimeoutTask.cancel(false);
            }
            for (SessionSubscription sub : state.subscriptions.values()) {
                sub.cancel();
                redisStreamListener.unregister(sub);
            }
            state.subscriptions.clear();
        }
        log.info("WebSocket disconnected: connId={}, code={}", connId, status.getCode());
    }

    private void sendError(WebSocketSession session, String id, String code, String message, Map<String, Object> details) throws IOException {
        sendMessage(session, new WsMessage.ErrorResponse(id, code, message, details));
    }

    SessionSubscription getSubscription(String connId, UUID sessionId) {
        ConnectionState state = connections.get(connId);
        return state != null ? state.subscriptions.get(sessionId) : null;
    }

    SessionSubscription getSubscriptionForSession(UUID sessionId) {
        for (ConnectionState state : connections.values()) {
            SessionSubscription sub = state.subscriptions.get(sessionId);
            if (sub != null) {
                return sub;
            }
        }
        return null;
    }

    public void closeSlowConsumer(WebSocketSession session) {
        String connId = session.getId();
        ConnectionState state = connections.remove(connId);
        if (state != null) {
            if (state.authTimeoutTask != null) {
                state.authTimeoutTask.cancel(false);
            }
            for (SessionSubscription sub : state.subscriptions.values()) {
                sub.cancel();
                redisStreamListener.unregister(sub);
            }
            state.subscriptions.clear();
        }
        try {
            if (session instanceof ConcurrentWebSocketSessionDecorator decorator) {
                decorator.getDelegate().close(STATUS_SLOW_CONSUMER);
            } else {
                session.close(STATUS_SLOW_CONSUMER);
            }
        } catch (IOException ignored) {}
    }

    private void sendMessage(WebSocketSession session, Object payload) throws IOException {
        if (!session.isOpen()) return;
        String json = objectMapper.writeValueAsString(payload);
        try {
            session.sendMessage(new TextMessage(json));
        } catch (SessionLimitExceededException ex) {
            log.warn("SessionLimitExceededException sending to conn {}. Closing delegate with 4420.", session.getId());
            closeSlowConsumer(session);
            throw ex;
        } catch (IllegalStateException ex) {
            log.info("Send failed, connection already closed for conn {}.", session.getId());
            throw ex;
        }
    }
}

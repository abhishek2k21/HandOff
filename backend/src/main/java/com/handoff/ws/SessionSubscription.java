package com.handoff.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.auth.WsTicketService.WsTicketPayload;
import com.handoff.events.Event;
import com.handoff.events.EventStore;
import com.handoff.session.Session;
import com.handoff.session.SessionRepository;
import com.handoff.ws.protocol.WsMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Manages an individual client's subscription to a session.
 * Enforces:
 * - Thread-safety via ReentrantLock (safe for virtual threads)
 * - Atomic cancellation and generation tracking (prevents late sends on re-subscribe)
 * - Step 3 live buffering and Step 6/7 gap filling
 * - Replay flow control (500 events OR 256 KB batch cap, low-water mark waiting)
 * - Queue overflow recovery via PostgreSQL catch-up
 */
public class SessionSubscription {

    private static final Logger log = LoggerFactory.getLogger(SessionSubscription.class);

    private static final int MAX_BATCH_EVENTS = 500;
    private static final int MAX_BATCH_BYTES = 256 * 1024; // 256 KB
    private static final int QUEUE_CAPACITY = 500;
    private static final int BUFFER_LOW_WATER_MARK = 64 * 1024; // 64 KB
    private static final int BUFFER_MAX_CAP = 512 * 1024; // 512 KB
    private static final long BUFFER_DRAIN_TIMEOUT_MS = 5000;

    public enum State {
        REPLAYING,
        LIVE,
        CANCELLED
    }

    private final UUID sessionId;
    private final WsTicketPayload user;
    private final long fromSeq;
    private final long generation;
    private final ConcurrentWebSocketSessionDecorator session;
    private final EventStore eventStore;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final RedisStreamListener streamListener;
    private final java.util.function.LongSupplier currentGenerationSupplier;

    private final ReentrantLock lock = new ReentrantLock();
    private final BlockingQueue<Event> liveQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean isWorkerRunning = new AtomicBoolean(false);

    private volatile State state = State.REPLAYING;
    private volatile long lastSentSeq;
    private volatile boolean behind = false;

    // --- Package-private test hooks ---
    Runnable testHookAfterRegistrationBeforeReaderActivation = null;
    Runnable testHookBeforeHistoryRead = null;
    Runnable testHookBeforeBufferFlush = null;

    public SessionSubscription(
            UUID sessionId,
            WsTicketPayload user,
            long fromSeq,
            long generation,
            ConcurrentWebSocketSessionDecorator session,
            EventStore eventStore,
            SessionRepository sessionRepository,
            ObjectMapper objectMapper,
            Executor executor,
            RedisStreamListener streamListener,
            java.util.function.LongSupplier currentGenerationSupplier
    ) {
        this.sessionId = sessionId;
        this.user = user;
        this.fromSeq = fromSeq;
        this.lastSentSeq = fromSeq;
        this.generation = generation;
        this.session = session;
        this.eventStore = eventStore;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.streamListener = streamListener;
        this.currentGenerationSupplier = currentGenerationSupplier;
    }

    public SessionSubscription(
            UUID sessionId,
            WsTicketPayload user,
            long fromSeq,
            long generation,
            ConcurrentWebSocketSessionDecorator session,
            EventStore eventStore,
            SessionRepository sessionRepository,
            ObjectMapper objectMapper,
            Executor executor,
            RedisStreamListener streamListener
    ) {
        this(sessionId, user, fromSeq, generation, session, eventStore, sessionRepository, objectMapper, executor, streamListener, null);
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public long getGeneration() {
        return generation;
    }

    public boolean isCurrentGeneration() {
        return currentGenerationSupplier == null || currentGenerationSupplier.getAsLong() == this.generation;
    }

    public long getLastSentSeq() {
        return lastSentSeq;
    }

    public State getState() {
        return state;
    }

    /**
     * Atomically cancels this subscription.
     */
    public void cancel() {
        lock.lock();
        try {
            state = State.CANCELLED;
            liveQueue.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Enqueues an incoming live event from Redis.
     * Called by the Redis reader thread; NEVER blocks or does socket I/O.
     */
    public void enqueueLiveEvent(Event event) {
        if (state == State.CANCELLED) {
            return;
        }

        boolean enqueued = liveQueue.offer(event);
        if (!enqueued) {
            log.warn("Per-subscription queue full for session {} conn {}. Marking behind for PG catch-up.",
                    sessionId, session.getId());
            behind = true;
        }

        scheduleWorker();
    }

    /**
     * Starts the subscription replay and live processing.
     */
    public void start() {
        executor.execute(this::runReplayAndDrain);
    }

    private void runReplayAndDrain() {
        lock.lock();
        try {
            if (state == State.CANCELLED) {
                return;
            }

            // 1. Send 'subscribed'
            Session sess = sessionRepository.findByIdWithoutOrg(sessionId).orElse(null);
            long sessionLastSeq = sess != null ? sess.lastSeq() : lastSentSeq;

            sendMessage(new WsMessage.SubscribedResponse(sessionId, sessionLastSeq));

            if (testHookBeforeHistoryRead != null) {
                testHookBeforeHistoryRead.run();
                Session refreshed = sessionRepository.findByIdWithoutOrg(sessionId).orElse(null);
                if (refreshed != null) {
                    sessionLastSeq = refreshed.lastSeq();
                }
            }

            // 2. Replay historical events from PostgreSQL
            long replayCursor = fromSeq;
            while (replayCursor < sessionLastSeq && state != State.CANCELLED) {
                // Wait for buffer to drain if needed (flow control)
                waitForBufferDrain();
                if (state == State.CANCELLED) {
                    break;
                }

                List<Event> batch = new ArrayList<>();
                int batchBytes = 0;

                List<Event> candidateEvents = eventStore.getEvents(sessionId, replayCursor, MAX_BATCH_EVENTS);
                if (candidateEvents.isEmpty()) {
                    break;
                }

                for (Event ev : candidateEvents) {
                    byte[] evBytes = objectMapper.writeValueAsBytes(ev);
                    if (!batch.isEmpty() && (batch.size() >= MAX_BATCH_EVENTS || batchBytes + evBytes.length > MAX_BATCH_BYTES)) {
                        break;
                    }
                    batch.add(ev);
                    batchBytes += evBytes.length;
                    replayCursor = ev.seq();
                }

                if (!batch.isEmpty()) {
                    sendMessage(new WsMessage.EventsBatchResponse(sessionId, batch));
                    lastSentSeq = replayCursor;
                }
            }

            if (testHookBeforeBufferFlush != null) {
                testHookBeforeBufferFlush.run();
            }

            // 3. Send 'caught_up'
            sendMessage(new WsMessage.CaughtUpResponse(sessionId, lastSentSeq));

            // 4. Transition to LIVE
            state = State.LIVE;

        } catch (Exception ex) {
            log.error("Error during replay for session {} conn {}", sessionId, session.getId(), ex);
        } finally {
            lock.unlock();
        }

        // 5. Drain any live events received during replay
        scheduleWorker();
    }

    private void scheduleWorker() {
        if (state == State.CANCELLED || state == State.REPLAYING) {
            return;
        }

        if (isWorkerRunning.compareAndSet(false, true)) {
            executor.execute(this::drainQueue);
        }
    }

    private void drainQueue() {
        try {
            lock.lock();
            try {
                if (state == State.CANCELLED) {
                    return;
                }

                // If marked behind, perform PG catch-up
                if (behind) {
                    catchUpFromDatabase();
                    behind = false;
                }

                Event event;
                while ((event = liveQueue.poll()) != null) {
                    if (state == State.CANCELLED) {
                        return;
                    }

                    long seq = event.seq();
                    if (seq <= lastSentSeq) {
                        // Duplicate; discard safely
                        continue;
                    }

                    if (seq > lastSentSeq + 1) {
                        // Gap detected; read missing range from PostgreSQL first (Step 7)
                        fillGapFromDatabase(lastSentSeq, seq - 1);
                    }

                    sendMessage(new WsMessage.LiveEventResponse(event));
                    lastSentSeq = seq;
                }

                // Check again if behind was set while draining
                if (behind) {
                    catchUpFromDatabase();
                    behind = false;
                }
            } finally {
                lock.unlock();
            }
        } catch (Exception ex) {
            log.error("Error draining live queue for session {} conn {}", sessionId, session.getId(), ex);
        } finally {
            isWorkerRunning.set(false);
            if (!liveQueue.isEmpty() || behind) {
                scheduleWorker();
            }
        }
    }

    private void fillGapFromDatabase(long from, long to) throws IOException {
        long cursor = from;
        while (cursor < to && state != State.CANCELLED) {
            int needed = (int) Math.min(to - cursor, MAX_BATCH_EVENTS);
            List<Event> missing = eventStore.getEvents(sessionId, cursor, needed);
            if (missing.isEmpty()) {
                break;
            }
            for (Event ev : missing) {
                if (ev.seq() > lastSentSeq) {
                    sendMessage(new WsMessage.LiveEventResponse(ev));
                    lastSentSeq = ev.seq();
                }
                cursor = ev.seq();
            }
        }
    }

    private void catchUpFromDatabase() throws IOException {
        Session sess = sessionRepository.findByIdWithoutOrg(sessionId).orElse(null);
        if (sess == null) return;
        long targetSeq = sess.lastSeq();
        if (targetSeq > lastSentSeq) {
            fillGapFromDatabase(lastSentSeq, targetSeq);
        }
    }

    private void waitForBufferDrain() {
        long startTime = System.currentTimeMillis();
        while (session.getBufferSize() > BUFFER_LOW_WATER_MARK) {
            if ((System.currentTimeMillis() - startTime) >= BUFFER_DRAIN_TIMEOUT_MS) {
                log.warn("Replay flow control timed out waiting for buffer to drain for session {} conn {}. Closing 4420.",
                        sessionId, session.getId());
                closeSlowConsumer();
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L); // 1 ms wait without Thread.sleep
        }
    }

    public void closeSlowConsumer() {
        cancel();
        try {
            session.close(HandOffWebSocketHandler.STATUS_SLOW_CONSUMER);
        } catch (IOException ignored) {}
    }

    public void sendMessage(Object payload) throws IOException {
        if (state == State.CANCELLED || !session.isOpen() || !isCurrentGeneration()) {
            return;
        }

        if (session.getBufferSize() > BUFFER_MAX_CAP) {
            log.warn("Slow consumer buffer cap exceeded ({} bytes) for session {} conn {}. Closing 4420.",
                    session.getBufferSize(), sessionId, session.getId());
            closeSlowConsumer();
            return;
        }

        String json = objectMapper.writeValueAsString(payload);
        session.sendMessage(new TextMessage(json));
    }
}

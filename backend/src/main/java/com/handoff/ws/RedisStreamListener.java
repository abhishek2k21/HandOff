package com.handoff.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.events.Event;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.stereotype.Component;

/**
 * Single daemon background thread polling active Redis streams via XREAD BLOCK 500ms.
 * - Dedicated Redis connection
 * - Newly activated streams start at "0-0" with seq de-duplication
 * - Stops reading streams with 0 subscribers
 * - Only enqueues to per-subscription queues, never performs socket I/O
 * - Restarts automatically on Redis connection errors with backoff
 */
@Component
public class RedisStreamListener implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamListener.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofMillis(500);

    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    // Track active subscribers per session
    private final ConcurrentHashMap<UUID, Set<SessionSubscription>> sessionSubscribers = new ConcurrentHashMap<>();

    // Track stream read offsets: streamKey -> lastStreamId
    private final ConcurrentHashMap<String, String> streamOffsets = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private Thread pollerThread;

    public RedisStreamListener(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        pollerThread = new Thread(this, "redis-stream-poller");
        pollerThread.setDaemon(true);
        pollerThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
    }

    public void register(SessionSubscription sub) {
        UUID sessionId = sub.getSessionId();
        sessionSubscribers.compute(sessionId, (id, currentSubs) -> {
            Set<SessionSubscription> set = currentSubs != null ? currentSubs : ConcurrentHashMap.newKeySet();
            set.add(sub);
            return set;
        });

        String streamKey = RedisStreamPublisher.getStreamKey(sessionId);
        // Start newly activated stream from "0-0" and rely on seq de-duplication
        streamOffsets.putIfAbsent(streamKey, "0-0");
    }

    public void unregister(SessionSubscription sub) {
        UUID sessionId = sub.getSessionId();
        sessionSubscribers.computeIfPresent(sessionId, (id, currentSubs) -> {
            currentSubs.remove(sub);
            if (currentSubs.isEmpty()) {
                String streamKey = RedisStreamPublisher.getStreamKey(sessionId);
                streamOffsets.remove(streamKey);
                return null;
            }
            return currentSubs;
        });
    }

    public Map<UUID, List<SessionSubscription>> getActiveSubscriptionsSnapshot() {
        Map<UUID, List<SessionSubscription>> snapshot = new HashMap<>();
        sessionSubscribers.forEach((sessionId, subs) -> {
            if (subs != null && !subs.isEmpty()) {
                snapshot.put(sessionId, new ArrayList<>(subs));
            }
        });
        return snapshot;
    }

    @Override
    public void run() {
        log.info("RedisStreamListener started.");
        while (running) {
            RedisConnection connection = null;
            try {
                connection = connectionFactory.getConnection();
                while (running) {
                    if (streamOffsets.isEmpty()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            if (!running) break;
                        }
                        continue;
                    }

                    // Prepare streams and offsets for XREAD
                    List<String> keys = new ArrayList<>(streamOffsets.keySet());
                    if (keys.isEmpty()) {
                        continue;
                    }

                    StreamOffset<byte[]>[] offsets = new StreamOffset[keys.size()];
                    for (int i = 0; i < keys.size(); i++) {
                        String key = keys.get(i);
                        String offset = streamOffsets.getOrDefault(key, "0-0");
                        offsets[i] = StreamOffset.create(key.getBytes(StandardCharsets.UTF_8), ReadOffset.from(offset));
                    }

                    List<ByteRecord> records;
                    try {
                        records = connection.streamCommands().xRead(
                                StreamReadOptions.empty().block(BLOCK_TIMEOUT).count(100),
                                offsets
                        );
                    } catch (Exception readEx) {
                        if (!running) break;
                        log.warn("Error reading from Redis streams, will reconnect after backoff", readEx);
                        break; // Break inner loop to close connection and backoff
                    }

                    if (records != null && !records.isEmpty()) {
                        for (ByteRecord record : records) {
                            processRecord(record);
                        }
                    }
                }
            } catch (Exception ex) {
                if (running) {
                    log.warn("Redis stream listener connection error, backing off 500ms before restart", ex);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        if (!running) break;
                    }
                }
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {}
                }
            }
        }
        log.info("RedisStreamListener stopped.");
    }

    private void processRecord(ByteRecord record) {
        String streamKey = new String(record.getStream(), StandardCharsets.UTF_8);
        String recordId = record.getId().getValue();
        streamOffsets.put(streamKey, recordId);

        Map<byte[], byte[]> rawMap = record.getValue();
        if (rawMap == null) return;

        byte[] dataBytes = null;
        for (Map.Entry<byte[], byte[]> entry : rawMap.entrySet()) {
            String field = new String(entry.getKey(), StandardCharsets.UTF_8);
            if ("data".equals(field)) {
                dataBytes = entry.getValue();
                break;
            }
        }

        if (dataBytes == null) return;

        try {
            Event event = objectMapper.readValue(dataBytes, Event.class);
            UUID sessionId = event.sessionId();

            Set<SessionSubscription> subscribers = sessionSubscribers.get(sessionId);
            if (subscribers != null) {
                for (SessionSubscription sub : subscribers) {
                    sub.enqueueLiveEvent(event);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to deserialize event from stream {}", streamKey, ex);
        }
    }
}

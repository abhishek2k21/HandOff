package com.handoff.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.events.Event;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes events to the Redis Stream: hg:s:{sessionId}:events with XADD ... MAXLEN ~ 1000.
 */
@Component
public class RedisStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamPublisher.class);
    public static final String STREAM_KEY_PREFIX = "hg:s:";
    public static final String STREAM_KEY_SUFFIX = ":events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisStreamPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public static String getStreamKey(UUID sessionId) {
        return STREAM_KEY_PREFIX + sessionId + STREAM_KEY_SUFFIX;
    }

    public void publish(Event event) {
        String streamKey = getStreamKey(event.sessionId());
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            Map<String, String> fields = Map.of(
                    "seq", String.valueOf(event.seq()),
                    "data", eventJson
            );

            redisTemplate.opsForStream().add(streamKey, fields);
            redisTemplate.opsForStream().trim(streamKey, 1000, true);
        } catch (Exception ex) {
            log.warn("Failed to publish event seq {} for session {} to Redis stream {}",
                    event.seq(), event.sessionId(), streamKey, ex);
        }
    }
}

package com.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Verifies that the application can connect to Redis and run basic commands.
 */
class RedisConnectionTest extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void canPingRedis() {
        // execute() runs a RedisCallback.  ping() sends the PING command
        // and Redis replies "PONG".
        // The explicit cast to RedisCallback<String> tells Java which
        // overloaded execute() method to use (there are several).
        String pong = redisTemplate.execute(
                (RedisCallback<String>) connection -> connection.ping());
        assertEquals("PONG", pong);
    }

    @Test
    void canSetAndGetValue() {
        redisTemplate.opsForValue().set("test:key", "hello");
        String value = redisTemplate.opsForValue().get("test:key");
        assertNotNull(value);
        assertEquals("hello", value);
        // Clean up
        redisTemplate.delete("test:key");
    }
}

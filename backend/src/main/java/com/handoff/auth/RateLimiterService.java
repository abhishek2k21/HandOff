package com.handoff.auth;

import com.handoff.common.RateLimitedException;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Enforces rate limits using Redis per docs/events.md section 16:
 * - Login attempts: 5 per minute per IP address
 * - WebSocket tickets: 10 per minute per user
 */
@Service
public class RateLimiterService {

    private static final String LOGIN_PREFIX = "hg:ratelimit:login:";
    private static final String TICKET_PREFIX = "hg:ratelimit:ticket:";

    private static final int MAX_LOGIN_ATTEMPTS_PER_MINUTE = 5;
    private static final int MAX_TICKETS_PER_MINUTE = 10;
    private static final long WINDOW_SECONDS = 60;

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return current
            """,
            Long.class
    );

    private final StringRedisTemplate redis;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void checkLoginRateLimit(String clientIp) {
        String key = LOGIN_PREFIX + (clientIp != null ? clientIp : "unknown");
        long count = incrementAndExpire(key, WINDOW_SECONDS);

        if (count > MAX_LOGIN_ATTEMPTS_PER_MINUTE) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = (ttl != null && ttl > 0) ? ttl : WINDOW_SECONDS;
            throw new RateLimitedException(
                    "Too many login attempts. Please try again later.",
                    retryAfter
            );
        }
    }

    public void checkWsTicketRateLimit(UUID userId) {
        String key = TICKET_PREFIX + userId;
        long count = incrementAndExpire(key, WINDOW_SECONDS);

        if (count > MAX_TICKETS_PER_MINUTE) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = (ttl != null && ttl > 0) ? ttl : WINDOW_SECONDS;
            throw new RateLimitedException(
                    "Too many WebSocket ticket requests. Please try again later.",
                    retryAfter
            );
        }
    }

    private long incrementAndExpire(String key, long windowSeconds) {
        Long count = redis.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(windowSeconds)
        );
        return count != null ? count : 1L;
    }
}

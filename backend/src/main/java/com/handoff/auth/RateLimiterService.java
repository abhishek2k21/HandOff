package com.handoff.auth;

import com.handoff.common.RateLimitedException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final StringRedisTemplate redis;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void checkLoginRateLimit(String clientIp) {
        String key = LOGIN_PREFIX + (clientIp != null ? clientIp : "unknown");
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
        }

        if (count != null && count > MAX_LOGIN_ATTEMPTS_PER_MINUTE) {
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
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
        }

        if (count != null && count > MAX_TICKETS_PER_MINUTE) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = (ttl != null && ttl > 0) ? ttl : WINDOW_SECONDS;
            throw new RateLimitedException(
                    "Too many WebSocket ticket requests. Please try again later.",
                    retryAfter
            );
        }
    }
}

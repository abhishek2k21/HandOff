package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.AbstractIntegrationTest;
import com.handoff.common.RateLimitedException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Tests login rate limiting (5 attempts per minute per IP address) per docs/events.md section 16.
 */
class LoginRateLimiterTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    @AfterEach
    void clearRateLimits() {
        Set<String> keys = redis.keys("hg:ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void sixthLoginAttemptFromSameIpIsRateLimited() throws Exception {
        LoginRequest req = new LoginRequest("test-" + UUID.randomUUID() + "@example.com", "pass");

        // 5 attempts allowed through to auth verification (status 401 UNAUTHENTICATED)
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/auth/login", HttpMethod.POST, new HttpEntity<>(req), String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        }

        // 6th attempt must be blocked by rate limiter (status 429 TOO_MANY_REQUESTS)
        ResponseEntity<String> rateLimitedRes = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(req), String.class);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rateLimitedRes.getStatusCode());

        JsonNode json = objectMapper.readTree(rateLimitedRes.getBody());
        assertEquals("RATE_LIMITED", json.get("error").get("code").asText());
        assertTrue(json.get("error").get("details").has("retryAfterSeconds"));
    }

    @Test
    void spoofedXForwardedForHeadersCannotBypassLoginRateLimit() throws Exception {
        LoginRequest req = new LoginRequest("spoof-" + UUID.randomUUID() + "@example.com", "wrongpass");

        // 5 attempts allowed through, each sending a different random X-Forwarded-For header
        for (int i = 0; i < 5; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Forwarded-For", "203.0.113." + (i + 1));
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/auth/login", HttpMethod.POST, new HttpEntity<>(req, headers), String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        }

        // 6th attempt from the same connection with yet another X-Forwarded-For header must still return 429
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "198.51.100.99");
        ResponseEntity<String> rateLimitedRes = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(req, headers), String.class);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rateLimitedRes.getStatusCode());

        JsonNode json = objectMapper.readTree(rateLimitedRes.getBody());
        assertEquals("RATE_LIMITED", json.get("error").get("code").asText());
        assertTrue(json.get("error").get("details").has("retryAfterSeconds"));
    }

    @Test
    void atomicRateLimitSetsTtlOnFirstHitAndLimitsOnSixthAttempt() {
        String testIp = "test-atomic-" + UUID.randomUUID();
        String redisKey = "hg:ratelimit:login:" + testIp;

        // 1st hit creates the key and sets TTL atomically
        rateLimiterService.checkLoginRateLimit(testIp);

        // After the first hit, the key must have a TTL of at most the window
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Long ttl = redis.getExpire(redisKey, TimeUnit.SECONDS);
                    assertNotNull(ttl);
                    assertTrue(ttl > 0 && ttl <= 60, "TTL must be at most 60 seconds, but was " + ttl);
                });

        // Attempts 2 to 5 succeed within the window
        for (int i = 2; i <= 5; i++) {
            rateLimiterService.checkLoginRateLimit(testIp);
        }

        // 6th attempt within the window is limited
        RateLimitedException ex = assertThrows(
                RateLimitedException.class,
                () -> rateLimiterService.checkLoginRateLimit(testIp)
        );
        assertTrue(ex.getRetryAfterSeconds() > 0 && ex.getRetryAfterSeconds() <= 60);
    }
}

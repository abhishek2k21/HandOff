package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

    @Test
    void sixthLoginAttemptFromSameIpIsRateLimited() throws Exception {
        String testIp = "192.168.1." + (100 + (int)(Math.random() * 100));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", testIp);

        LoginRequest req = new LoginRequest("test-" + UUID.randomUUID() + "@example.com", "pass");

        // 5 attempts allowed through to auth verification (status 401 UNAUTHENTICATED)
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/auth/login", HttpMethod.POST, new HttpEntity<>(req, headers), String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        }

        // 6th attempt must be blocked by rate limiter (status 429 TOO_MANY_REQUESTS)
        ResponseEntity<String> rateLimitedRes = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(req, headers), String.class);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rateLimitedRes.getStatusCode());

        JsonNode json = objectMapper.readTree(rateLimitedRes.getBody());
        assertEquals("RATE_LIMITED", json.get("error").get("code").asText());
        assertTrue(json.get("error").get("details").has("retryAfterSeconds"));
    }
}

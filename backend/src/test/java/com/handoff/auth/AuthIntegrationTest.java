package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end integration tests for authentication, password hashing,
 * token rotation, reuse detection, and error models.
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerStoresPasswordHashedAndCreatesAdminMembership() {
        String email = "maya.reg-" + System.nanoTime() + "@example.com";
        String rawPassword = "Password123!";
        RegisterRequest req = new RegisterRequest(email, rawPassword, "Maya", "Acme Support");

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/register", req, AuthResponse.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().accessToken());
        assertNotNull(response.getBody().refreshToken());
        assertEquals("Maya", response.getBody().user().name());
        assertEquals(Role.ADMIN, response.getBody().user().role());

        // Verify database: raw password must NEVER appear in DB, only BCrypt hash
        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?",
                String.class, email.toLowerCase());

        assertNotNull(passwordHash);
        assertNotEquals(rawPassword, passwordHash);
        assertTrue(passwordEncoder.matches(rawPassword, passwordHash));
    }

    @Test
    void registerWithDuplicateEmailReturnsValidationFailed() {
        String email = "duplicate-" + System.nanoTime() + "@example.com";
        RegisterRequest req1 = new RegisterRequest(email, "Password123!", "User 1", "Org 1");
        restTemplate.postForEntity("/api/auth/register", req1, AuthResponse.class);

        // Case-insensitive duplicate email attempt
        RegisterRequest req2 = new RegisterRequest(email.toUpperCase(), "Password123!", "User 2", "Org 2");
        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/register", req2, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("VALIDATION_FAILED"));
        assertTrue(response.getBody().contains("Email is already registered"));
    }

    @Test
    void loginWithValidCredentialsReturnsTokensAndUser() {
        String email = "login.test-" + System.nanoTime() + "@example.com";
        RegisterRequest regReq = new RegisterRequest(email, "Password123!", "Login User", "Login Org");
        restTemplate.postForEntity("/api/auth/register", regReq, AuthResponse.class);

        LoginRequest loginReq = new LoginRequest(email, "Password123!");
        ResponseEntity<AuthResponse> loginRes = restTemplate.postForEntity(
                "/api/auth/login", loginReq, AuthResponse.class);

        assertEquals(HttpStatus.OK, loginRes.getStatusCode());
        assertNotNull(loginRes.getBody());
        assertNotNull(loginRes.getBody().accessToken());
        assertNotNull(loginRes.getBody().refreshToken());
        assertEquals(900, loginRes.getBody().expiresInSeconds());
        assertEquals("Login User", loginRes.getBody().user().name());
        assertEquals(Role.ADMIN, loginRes.getBody().user().role());
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthenticated() {
        String email = "wrong.pass-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(email, "Password123!", "User", "Org"), AuthResponse.class);

        LoginRequest loginReq = new LoginRequest(email, "WrongPassword!");
        ResponseEntity<String> loginRes = restTemplate.postForEntity("/api/auth/login", loginReq, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginRes.getStatusCode());
        assertTrue(loginRes.getBody().contains("UNAUTHENTICATED"));
        assertTrue(loginRes.getBody().contains("Invalid email or password"));
    }

    @Test
    void loginWithUnknownEmailReturnsSameUnauthenticatedError() {
        LoginRequest loginReq = new LoginRequest("unknown-" + System.nanoTime() + "@example.com", "Password123!");
        ResponseEntity<String> loginRes = restTemplate.postForEntity("/api/auth/login", loginReq, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginRes.getStatusCode());
        assertTrue(loginRes.getBody().contains("UNAUTHENTICATED"));
        assertTrue(loginRes.getBody().contains("Invalid email or password"));
    }

    @Test
    void refreshTokenRotationReplacesOldToken() {
        String email = "rotate-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(email, "Password123!", "Rotate User", "Rotate Org"), AuthResponse.class);

        String initialRefreshToken = reg.getBody().refreshToken();

        // Refresh call
        ResponseEntity<AuthResponse> refreshRes = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(initialRefreshToken), AuthResponse.class);

        assertEquals(HttpStatus.OK, refreshRes.getStatusCode());
        assertNotNull(refreshRes.getBody());
        assertNotNull(refreshRes.getBody().accessToken());
        String newRefreshToken = refreshRes.getBody().refreshToken();
        assertNotEquals(initialRefreshToken, newRefreshToken);

        // Trying to refresh with the new token works
        ResponseEntity<AuthResponse> refreshRes2 = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(newRefreshToken), AuthResponse.class);
        assertEquals(HttpStatus.OK, refreshRes2.getStatusCode());
    }

    @Test
    void reuseDetectionRevokesAllTokensWhenOldRefreshTokenIsReused() {
        String email = "reuse-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(email, "Password123!", "Reuse User", "Reuse Org"), AuthResponse.class);

        String token1 = reg.getBody().refreshToken();

        // Legitimate rotation: token1 -> token2
        ResponseEntity<AuthResponse> rotated = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(token1), AuthResponse.class);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        String token2 = rotated.getBody().refreshToken();

        // Attacker (or replay) tries to use token1 again (reuse detected!)
        ResponseEntity<String> reuseAttempt = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(token1), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, reuseAttempt.getStatusCode());

        // Now token2 must also be revoked because reuse detection invalidated the entire token family!
        ResponseEntity<String> token2Attempt = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(token2), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, token2Attempt.getStatusCode());
    }

    @Test
    void concurrentRefreshOfSameTokenProducesExactlyOneSuccess() throws Exception {
        String email = "concurrent.refresh-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(email, "Password123!", "Concurrent User", "Concurrent Org"), AuthResponse.class);

        String token = reg.getBody().refreshToken();

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ResponseEntity<String> res = restTemplate.postForEntity(
                        "/api/auth/refresh", new RefreshRequest(token), String.class);
                return res.getStatusCode().value();
            });
        }

        List<Future<Integer>> results = executor.invokeAll(tasks);
        executor.shutdown();

        int successCount = 0;
        int failCount = 0;

        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 200) {
                successCount++;
            } else if (status == 401) {
                failCount++;
            }
        }

        assertEquals(1, successCount, "Exactly one concurrent refresh must succeed");
        assertEquals(threads - 1, failCount, "Other concurrent refreshes must be rejected");
    }

    @Test
    void logoutRevokesRefreshTokenAndIsIdempotent() {
        String email = "logout-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(email, "Password123!", "Logout User", "Logout Org"), AuthResponse.class);

        String refreshToken = reg.getBody().refreshToken();

        // First logout call
        ResponseEntity<Void> logout1 = restTemplate.postForEntity(
                "/api/auth/logout", new LogoutRequest(refreshToken), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, logout1.getStatusCode());

        // Refresh with logged-out token must fail
        ResponseEntity<String> refreshRes = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(refreshToken), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, refreshRes.getStatusCode());

        // Idempotent: repeated logout call also succeeds with 204
        ResponseEntity<Void> logout2 = restTemplate.postForEntity(
                "/api/auth/logout", new LogoutRequest(refreshToken), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, logout2.getStatusCode());
    }

    @Test
    void correlationIdHeaderAndMdcPropagatedInErrorResponses() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "custom-corr-12345");
        HttpEntity<LoginRequest> entity = new HttpEntity<>(
                new LoginRequest("bad-" + System.nanoTime() + "@example.com", "pass"), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, entity, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("custom-corr-12345", response.getHeaders().getFirst("X-Correlation-Id"));

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("custom-corr-12345", json.get("error").get("correlationId").asText());
    }

    @Test
    void protectedEndpointWithoutTokenReturnsStandardUnauthenticatedError() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/test-roles/viewer", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("UNAUTHENTICATED", json.get("error").get("code").asText());
        assertNotNull(json.get("error").get("correlationId").asText());
    }
}

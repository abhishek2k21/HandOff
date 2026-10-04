package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests verifying JWT secret length requirements and dev profile fallbacks.
 */
class JwtSecretValidationTest {

    @Test
    void missingSecretOutsideDevThrowsIllegalStateException() {
        MockEnvironment env = new MockEnvironment(); // no active profiles
        JwtService service = new JwtService("", 900, 604800, 30, env);
        assertThrows(IllegalStateException.class, service::init);
    }

    @Test
    void shortSecretOutsideDevThrowsIllegalStateException() {
        MockEnvironment env = new MockEnvironment();
        JwtService service = new JwtService("short-secret-less-than-32-b", 900, 604800, 30, env);
        assertThrows(IllegalStateException.class, service::init);
    }

    @Test
    void missingSecretUnderDevProfileAllowedWithDefaultDevSecret() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        JwtService service = new JwtService("", 900, 604800, 30, env);
        assertDoesNotThrow(service::init);
    }

    @Test
    void validThirtyTwoByteSecretInitializesSuccessfully() {
        MockEnvironment env = new MockEnvironment();
        JwtService service = new JwtService("this-is-a-valid-32-byte-secret-key!!", 900, 604800, 30, env);
        assertDoesNotThrow(service::init);
    }
}

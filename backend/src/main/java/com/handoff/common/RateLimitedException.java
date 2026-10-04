package com.handoff.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 429 RATE_LIMITED error.
 */
public class RateLimitedException extends ApiException {

    public RateLimitedException(String message, long retryAfterSeconds) {
        super("RATE_LIMITED", message, HttpStatus.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}

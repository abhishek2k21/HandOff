package com.handoff.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base exception for known API error responses.
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(String code, String message, HttpStatus status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details != null ? details : Map.of();
    }

    public ApiException(String code, String message, HttpStatus status) {
        this(code, message, status, Map.of());
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}

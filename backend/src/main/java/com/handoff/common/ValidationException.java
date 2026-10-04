package com.handoff.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 400 VALIDATION_FAILED error.
 */
public class ValidationException extends ApiException {

    public ValidationException(String message, Map<String, Object> fieldErrors) {
        super("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST, Map.of("fields", fieldErrors));
    }
}

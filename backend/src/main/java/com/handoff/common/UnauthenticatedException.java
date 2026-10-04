package com.handoff.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 401 UNAUTHENTICATED error.
 */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String message) {
        super("UNAUTHENTICATED", message, HttpStatus.UNAUTHORIZED);
    }

    public UnauthenticatedException(String code, String message) {
        super(code, message, HttpStatus.UNAUTHORIZED);
    }

    public UnauthenticatedException(String code, String message, Map<String, Object> details) {
        super(code, message, HttpStatus.UNAUTHORIZED, details);
    }
}

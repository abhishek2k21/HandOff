package com.handoff.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 403 FORBIDDEN error.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    public ForbiddenException(String code, String message, Map<String, Object> details) {
        super(code, message, HttpStatus.FORBIDDEN, details);
    }
}

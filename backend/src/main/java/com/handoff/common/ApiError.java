package com.handoff.common;

import java.util.Map;

/**
 * Standard error response body defined in docs/events.md section 15.1.
 *
 * Example:
 * {
 *   "error": {
 *     "code": "FORBIDDEN",
 *     "message": "Role cannot steer",
 *     "details": {},
 *     "correlationId": "7c1f..."
 *   }
 * }
 */
public record ApiError(ErrorDetail error) {

    public static ApiError of(String code, String message, Map<String, Object> details, String correlationId) {
        return new ApiError(new ErrorDetail(code, message, details != null ? details : Map.of(), correlationId));
    }

    public record ErrorDetail(
            String code,
            String message,
            Map<String, Object> details,
            String correlationId
    ) {}
}

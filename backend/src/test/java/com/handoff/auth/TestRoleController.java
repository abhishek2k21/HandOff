package com.handoff.auth;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller used in integration tests to verify @PreAuthorize method security and roles.
 */
@RestController
@RequestMapping("/api/test-roles")
public class TestRoleController {

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> adminOnly() {
        return Map.of("role", "ADMIN");
    }

    @GetMapping("/approver")
    @PreAuthorize("hasAnyRole('APPROVER', 'ADMIN')")
    public Map<String, String> approverOrAbove() {
        return Map.of("role", "APPROVER_OR_ABOVE");
    }

    @GetMapping("/operator")
    @PreAuthorize("hasAnyRole('OPERATOR', 'APPROVER', 'ADMIN')")
    public Map<String, String> operatorOrAbove() {
        return Map.of("role", "OPERATOR_OR_ABOVE");
    }

    @GetMapping("/viewer")
    @PreAuthorize("hasAnyRole('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')")
    public Map<String, String> viewerOrAbove() {
        return Map.of("role", "VIEWER_OR_ABOVE");
    }
}

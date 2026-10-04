package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Verifies role-based access control and method security across all four roles:
 * VIEWER < OPERATOR < APPROVER < ADMIN.
 */
class RoleAuthorizationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void roleHierarchyEnforcedAcrossEndpoints() throws Exception {
        UUID orgId = UUID.randomUUID();
        String viewerToken = jwtService.generateAccessToken(UUID.randomUUID(), orgId, Role.VIEWER, "Viewer");
        String operatorToken = jwtService.generateAccessToken(UUID.randomUUID(), orgId, Role.OPERATOR, "Operator");
        String approverToken = jwtService.generateAccessToken(UUID.randomUUID(), orgId, Role.APPROVER, "Approver");
        String adminToken = jwtService.generateAccessToken(UUID.randomUUID(), orgId, Role.ADMIN, "Admin");

        // 1. Viewer role
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/viewer", viewerToken).getStatusCode());
        assertForbidden("/api/test-roles/operator", viewerToken);
        assertForbidden("/api/test-roles/approver", viewerToken);
        assertForbidden("/api/test-roles/admin", viewerToken);

        // 2. Operator role
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/viewer", operatorToken).getStatusCode());
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/operator", operatorToken).getStatusCode());
        assertForbidden("/api/test-roles/approver", operatorToken);
        assertForbidden("/api/test-roles/admin", operatorToken);

        // 3. Approver role
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/viewer", approverToken).getStatusCode());
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/operator", approverToken).getStatusCode());
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/approver", approverToken).getStatusCode());
        assertForbidden("/api/test-roles/admin", approverToken);

        // 4. Admin role
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/viewer", adminToken).getStatusCode());
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/operator", adminToken).getStatusCode());
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/approver", adminToken).getStatusCode());
        assertEquals(HttpStatus.OK, getWithToken("/api/test-roles/admin", adminToken).getStatusCode());
    }

    private ResponseEntity<String> getWithToken(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void assertForbidden(String url, String token) throws Exception {
        ResponseEntity<String> response = getWithToken(url, token);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("FORBIDDEN", json.get("error").get("code").asText());
        assertNotNull(json.get("error").get("correlationId").asText());
    }
}

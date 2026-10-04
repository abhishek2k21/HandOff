package com.handoff.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handoff.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

class NoDefaultSecurityUserTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void applicationContextHasNoDefaultInMemoryUserDetailsManager() {
        assertTrue(
                applicationContext.getBeansOfType(InMemoryUserDetailsManager.class).isEmpty(),
                "InMemoryUserDetailsManager bean must not exist in context"
        );
        assertTrue(
                applicationContext.getBeansOfType(UserDetailsService.class).isEmpty(),
                "No default UserDetailsService bean should be registered"
        );
    }

    @Test
    void basicAuthWithDefaultUserIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        String basicAuth = Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/ws-ticket",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}

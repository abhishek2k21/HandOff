package com.handoff.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Unit test for HealthController.
 *
 * @WebMvcTest loads only the web layer (controllers, filters, etc.)
 * without starting a real server or connecting to databases.
 * We mock HealthService so no Postgres or Redis is needed.
 *
 * This is faster than an integration test — good for quick feedback.
 */
import org.springframework.context.annotation.Import;
import com.handoff.auth.SecurityConfig;
import com.handoff.auth.JwtAuthenticationFilter;
import com.handoff.auth.RestAuthenticationEntryPoint;
import com.handoff.auth.RestAccessDeniedHandler;
import com.handoff.common.CorrelationIdFilter;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JwtAuthenticationFilter.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthService healthService;

    @Test
    void healthUp() throws Exception {
        Map<String, String> up = new LinkedHashMap<>();
        up.put("status", "UP");
        up.put("database", "UP");
        up.put("redis", "UP");
        when(healthService.check()).thenReturn(up);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.redis").value("UP"));
    }

    @Test
    void healthDown() throws Exception {
        Map<String, String> down = new LinkedHashMap<>();
        down.put("status", "DOWN");
        down.put("database", "UP");
        down.put("redis", "DOWN");
        when(healthService.check()).thenReturn(down);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.redis").value("DOWN"));
    }
}

package com.handoff.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing auth endpoints and WebSocket ticket generation.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final WsTicketService wsTicketService;

    public AuthController(AuthService authService, WsTicketService wsTicketService) {
        this.authService = authService;
        this.wsTicketService = wsTicketService;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse response = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest request
    ) {
        String clientIp = extractClientIp(request);
        AuthResponse response = authService.login(req, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        AuthResponse response = authService.refresh(req.refreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest req) {
        String token = (req != null) ? req.refreshToken() : null;
        authService.logout(token);
    }

    @PostMapping("/ws-ticket")
    public ResponseEntity<WsTicketResponse> wsTicket(@AuthenticationPrincipal UserPrincipal principal) {
        WsTicketResponse response = wsTicketService.createTicket(principal);
        return ResponseEntity.ok(response);
    }

    private String extractClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}

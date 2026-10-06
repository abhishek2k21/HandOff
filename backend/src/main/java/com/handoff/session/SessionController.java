package com.handoff.session;

import com.handoff.auth.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'APPROVER', 'ADMIN')")
    public ResponseEntity<SessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        SessionResponse response = sessionService.createSession(
                principal.organizationId(),
                principal.userId(),
                principal.displayName(),
                req
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<SessionResponse> items = sessionService.listSessions(principal.organizationId(), limit);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')")
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        SessionResponse response = sessionService.getSession(principal.organizationId(), id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')")
    public ResponseEntity<EventsResponse> getEvents(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long fromSeq,
            @RequestParam(defaultValue = "200") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        EventsResponse response = sessionService.getEvents(principal.organizationId(), id, fromSeq, limit);
        return ResponseEntity.ok(response);
    }
}

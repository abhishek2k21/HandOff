package com.handoff.ticket;

import com.handoff.auth.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketRepository ticketRepository;

    public TicketController(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> getTickets(@AuthenticationPrincipal UserPrincipal principal) {
        List<TicketResponse> items = ticketRepository.findAllByOrg(principal.organizationId())
                .stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(Map.of("items", items));
    }
}

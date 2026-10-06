package com.handoff.ticket;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository {
    Optional<Ticket> findById(UUID orgId, String ticketId);
    List<Ticket> findAllByOrg(UUID orgId);
    void createTicket(Ticket ticket);
    void updateStatus(UUID orgId, String ticketId, String status);
    void addNote(UUID orgId, String ticketId, String author, String text);
    List<Map<String, Object>> getNotes(UUID orgId, String ticketId);
    void resetTicketsForDemo(UUID orgId, List<String> ticketIds);
}

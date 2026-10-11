package com.handoff.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {
    void createSession(Session session);
    Optional<Session> findById(UUID orgId, UUID sessionId);
    Optional<Session> findByIdWithoutOrg(UUID sessionId);
    List<Session> findAllByOrg(UUID orgId, int limit);
    boolean hasActiveSessionForTicket(UUID orgId, String ticketId);
    java.util.Map<UUID, Long> findLastSeqsByIds(java.util.Collection<UUID> sessionIds);
}

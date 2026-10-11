package com.handoff.ws;

import com.handoff.session.SessionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically reconciles WebSocket subscriptions with PostgreSQL.
 *
 * Runs on a fixed delay (default 2000 ms, configurable via handoff.ws.reconciliation-interval-ms).
 * Takes a snapshot of active subscriptions from RedisStreamListener, executes ONE batch query
 * (SELECT id, last_seq FROM sessions WHERE id = ANY(?)), and delegates catch-up to each
 * subscription via catchUpIfBehind(targetLastSeq).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final RedisStreamListener streamListener;
    private final SessionRepository sessionRepository;
    private volatile boolean enabled = true;

    public ReconciliationService(RedisStreamListener streamListener, SessionRepository sessionRepository) {
        this.streamListener = streamListener;
        this.sessionRepository = sessionRepository;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Scheduled(fixedDelayString = "${handoff.ws.reconciliation-interval-ms:2000}")
    public void runReconciliation() {
        if (!enabled) {
            return;
        }

        try {
            Map<UUID, List<SessionSubscription>> snapshot = streamListener.getActiveSubscriptionsSnapshot();
            if (snapshot.isEmpty()) {
                return;
            }

            Set<UUID> sessionIds = snapshot.keySet();
            Map<UUID, Long> lastSeqs = sessionRepository.findLastSeqsByIds(sessionIds);

            for (Map.Entry<UUID, List<SessionSubscription>> entry : snapshot.entrySet()) {
                UUID sessionId = entry.getKey();
                Long targetLastSeq = lastSeqs.get(sessionId);
                if (targetLastSeq == null || targetLastSeq <= 0L) {
                    continue;
                }

                for (SessionSubscription sub : entry.getValue()) {
                    sub.catchUpIfBehind(targetLastSeq);
                }
            }
        } catch (Exception ex) {
            log.error("Error during WebSocket reconciliation job run", ex);
        }
    }
}

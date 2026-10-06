package com.handoff.agent;

import com.handoff.events.ActorKind;
import com.handoff.events.EventStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a tool and records its TOOL_RESULT event inside a single transaction.
 * If recording the TOOL_RESULT fails, the tool's database modifications are rolled back.
 * (Rule R7 & Requirement 6)
 */
@Service
public class ToolExecutionService {

    private final ToolRuntime toolRuntime;
    private final EventStore eventStore;

    public ToolExecutionService(ToolRuntime toolRuntime, EventStore eventStore) {
        this.toolRuntime = toolRuntime;
        this.eventStore = eventStore;
    }

    @Transactional
    public void executeAndRecord(
            UUID orgId,
            UUID sessionId,
            String toolCallId,
            String toolName,
            Map<String, Object> args
    ) {
        // 1. Tool execution (database effects such as add_note, close_ticket)
        Map<String, Object> result = toolRuntime.execute(orgId, toolName, args);

        // 2. Append TOOL_RESULT event in the SAME transaction
        Map<String, Object> payload = new HashMap<>();
        payload.put("toolCallId", toolCallId);
        payload.put("ok", true);
        payload.put("result", result);
        payload.put("error", null);

        eventStore.append(
                sessionId,
                "TOOL_RESULT",
                ActorKind.SYSTEM,
                "tool-runtime",
                "Tool Runtime",
                null,
                payload
        );
    }
}

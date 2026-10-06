# HandOff: Event Schema and Protocol Specification (events.md)

## 1. Document Control

| Field | Value |
|---|---|
| Document | docs/events.md |
| Protocol version | 1 |
| Status | Approved for MVP build |
| Date | 2026-10-03 |
| Owner | Abhishek Kumar (solo developer / product owner) |
| Related documents | prd.md (requirements), AGENTS.md (agent working rules) |
| Change control | This file is the contract between backend and frontend. It must not be changed without the product owner's approval (see section 21). |

### 1.1 Revision History

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-03 | First complete specification. Supersedes the earlier draft. Changes from the draft: the WebSocket ticket is sent as the first message instead of in the URL (matches the "no tokens in URLs" rule); added CONTROL_RELEASED, SESSION_FAILED, BUDGET_EXCEEDED and AGENT_RECOVERED events; added the `commands` table for durable idempotency; added `expectedControllerId` to TAKE_CONTROL. |

---

## 2. Purpose and Conventions

This document defines every message that moves through HandOff: the stored events, the commands users send, the WebSocket protocol, the REST contracts, the database rules that enforce ordering, and worked examples that tests can reuse as fixtures.

### 2.1 Conventions

| Topic | Convention |
|---|---|
| Keywords | MUST, MUST NOT, SHOULD, MAY are used in their usual specification sense |
| Format | JSON, UTF-8 |
| Field names | camelCase |
| Enum values | UPPER_SNAKE_CASE |
| IDs | UUID v4 strings, except `ticketId` (string such as `T-102`), `toolCallId`, `messageId`, and `commandId` (client-generated string, 1 to 64 characters) |
| Timestamps | ISO-8601 in UTC with milliseconds, for example `2026-10-03T10:15:30.123Z` |
| Money | `amount` as a number in the stated `currency` (default `INR`) |
| Unknown fields | Receivers MUST ignore fields they do not recognize |
| Unknown event types | Clients MUST still advance `seq` and render a generic "unknown event" row |

---

## 3. Design Guarantees

These map to the core rules in prd.md section 17.1.

| Rule | Guarantee | Where enforced in this document |
|---|---|---|
| R1 | Events are append-only, per-session `seq` is gapless and unique | Sections 4, 10 |
| R2 | Delivery is at-least-once; application on the client is exactly-once; reconnect replays missed events | Section 9 |
| R3 | Exactly one decision per approval | Section 12 |
| R4 | At most one controller per session | Section 11 |
| R5 | Every command is authorized on the server | Section 8 |
| R6 | Risky tool calls always create an approval and pause the agent | Sections 12, 13 |
| R7 | Every human action and agent tool call is an event | Sections 5, 6 |

---

## 4. The Event Envelope

Every event, whether stored, replayed, or streamed live, has this shape.

```json
{
  "sessionId": "5b0e3c1e-8a52-4b0d-9c55-2f4d6f1b7a10",
  "seq": 12,
  "type": "TOOL_CALL",
  "actor": { "kind": "AGENT", "id": "agent-scripted-1", "name": "Scripted Agent" },
  "commandId": null,
  "payload": { },
  "createdAt": "2026-10-03T10:15:30.123Z"
}
```

| Field | Type | Rules |
|---|---|---|
| sessionId | UUID | The session this event belongs to |
| seq | integer | Starts at 1 per session, increases by exactly 1, never skipped, never reused |
| type | enum | One of the types in section 5 |
| actor.kind | enum | `AGENT`, `USER`, or `SYSTEM` |
| actor.id | string | User id for `USER`; agent id for `AGENT`; `system` or `tool-runtime` for `SYSTEM` |
| actor.name | string or null | Display name at the time of the event (denormalized for audit readability) |
| commandId | string or null | The client command that caused this event, if any |
| payload | object | Type-specific; see section 5.2 |
| createdAt | timestamp | Server time when the event was committed |

### 4.1 Immutability

- Events MUST NOT be updated or deleted by the application. A database trigger enforces this (section 10.1).
- A correction is a new event, never an edit.
- Payload size limit: 64 KB per event.

---

## 5. Event Types

### 5.1 Catalog

| Type | Meaning | Actor kind | Changes status |
|---|---|---|---|
| SESSION_STARTED | A session was created | USER | to RUNNING |
| AGENT_TEXT | A chunk of agent output | AGENT | no |
| TOOL_CALL | The agent requested a tool | AGENT | no |
| TOOL_RESULT | The tool finished | SYSTEM (`tool-runtime`) | no |
| STEER | A human instruction for the agent | USER | no |
| HUMAN_MESSAGE | A human comment that does not steer the agent | USER | no |
| PAUSED | The run was paused | USER | to PAUSED |
| RESUMED | The run continued | USER | to RUNNING |
| APPROVAL_REQUESTED | A risky tool call is waiting for a human | SYSTEM | to AWAITING_APPROVAL |
| APPROVAL_DECIDED | A decision was recorded | USER or SYSTEM (timeout) | to RUNNING |
| CONTROL_TAKEN | A user became the controller | USER | no |
| CONTROL_RELEASED | The controller lease ended without a successor | SYSTEM | no |
| HANDOFF_SUMMARY | Control was handed to a teammate with a summary | USER | to HANDED_OFF |
| BUDGET_EXCEEDED | A step or token budget was reached | SYSTEM | no |
| AGENT_RECOVERED | The agent loop resumed after a server restart | SYSTEM | no |
| ERROR | Something failed | SYSTEM or AGENT | no |
| SESSION_COMPLETED | The session finished successfully | AGENT or SYSTEM | to COMPLETED |
| SESSION_FAILED | The session ended unsuccessfully | SYSTEM | to FAILED |

### 5.2 Payload Schemas

Fields marked `?` are optional and may be absent or null.

**SESSION_STARTED**
```json
{
  "ticketId": "T-102",
  "agentType": "SCRIPTED",
  "scenario": "REFUND_APPROVAL",
  "stepBudget": 20,
  "tokenBudget": 20000,
  "createdBy": "<userId>"
}
```
`scenario` is set only for `SCRIPTED` agents.

**AGENT_TEXT**
```json
{ "messageId": "m-1", "text": "Checking order 8841...", "final": false }
```
Chunks of one message share a `messageId`. The last chunk has `final: true`. Clients concatenate chunks in `seq` order.

**TOOL_CALL**
```json
{
  "toolCallId": "tc-1",
  "tool": "issue_refund",
  "args": { "orderId": "8841", "amount": 4200, "currency": "INR", "reason": "Item arrived damaged" },
  "risky": true,
  "riskReason": "Refund amount 4200 exceeds limit 2000"
}
```
`riskReason` is null when `risky` is false.

**TOOL_RESULT**
```json
{ "toolCallId": "tc-1", "ok": true, "result": { "refundId": "R-5521" }, "error": null }
```
When `ok` is false, `result` is null and `error` is a short message.

**STEER**
```json
{ "instruction": "Offer store credit instead of a refund." }
```
Maximum 2,000 characters.

**HUMAN_MESSAGE**
```json
{ "text": "Customer called again, please prioritize." }
```
Maximum 1,000 characters. It does not reach the agent.

**PAUSED / RESUMED**
```json
{ "reason": "Checking policy with the team lead" }
```
`reason` is optional, maximum 500 characters.

**APPROVAL_REQUESTED**
```json
{
  "approvalId": "<uuid>",
  "toolCallId": "tc-1",
  "tool": "issue_refund",
  "args": { "orderId": "8841", "amount": 4200, "currency": "INR", "reason": "Item arrived damaged" },
  "summary": "Refund 4200 INR on order 8841",
  "expiresAt": "2026-10-03T10:25:30.000Z"
}
```

**APPROVAL_DECIDED**
```json
{
  "approvalId": "<uuid>",
  "toolCallId": "tc-1",
  "decision": "APPROVED",
  "decidedBy": "HUMAN",
  "note": "Within policy for damaged goods"
}
```
`decision` is `APPROVED` or `DENIED`. `decidedBy` is `HUMAN` or `TIMEOUT`. A timeout produces `DENIED` with actor kind `SYSTEM`.

**CONTROL_TAKEN**
```json
{ "fromUserId": "<userId or null>", "toUserId": "<userId>", "via": "TAKE_CONTROL" }
```
`via` is `SESSION_START`, `TAKE_CONTROL`, or `HANDOFF`.

**CONTROL_RELEASED**
```json
{ "userId": "<userId>", "reason": "LEASE_EXPIRED" }
```

**HANDOFF_SUMMARY**
```json
{
  "fromUserId": "<userId>",
  "toUserId": "<userId>",
  "note": "End of my shift",
  "summary": "Agent verified order 8841, refund of 4200 INR approved by Ravi, awaiting customer reply."
}
```
The `summary` is generated by the server (template-based for scripted sessions, LLM-generated for LLM sessions).

**BUDGET_EXCEEDED**
```json
{ "kind": "STEPS", "limit": 20, "used": 20 }
```
`kind` is `STEPS`, `TOKENS`, or `DAILY_CAP`.

**AGENT_RECOVERED**
```json
{ "resumedAfterSeq": 41, "reason": "SERVER_RESTART" }
```

**ERROR**
```json
{ "code": "LLM_UNAVAILABLE", "message": "LLM request timed out", "recoverable": true }
```
Codes used in events: `LLM_UNAVAILABLE`, `TOOL_FAILED`, `INTERNAL`.

**SESSION_COMPLETED**
```json
{ "outcome": "REFUNDED", "summary": "Refund of 4200 INR issued for order 8841." }
```

**SESSION_FAILED**
```json
{ "reason": "BUDGET_EXCEEDED", "message": "Step budget of 20 reached" }
```
`reason` is `BUDGET_EXCEEDED`, `AGENT_ERROR`, or `INTERNAL`.

---

## 6. Actors and Production Rules

| Rule | Detail |
|---|---|
| Only the server creates events | Clients send commands (section 8); the server decides which events result |
| Human events carry the acting user | `actor.kind = USER`, `actor.id = userId`, `actor.name = display name` |
| Agent events carry the agent id | `agent-scripted-1` or `agent-llm-1` |
| System events | Budgets, timeouts, lease expiry, tool results, recovery, failures |
| Atomic groups | Events produced by one command are written in one transaction with consecutive `seq` values (for example, HANDOFF produces HANDOFF_SUMMARY then CONTROL_TAKEN) |

---

## 7. Session State Machine

### 7.1 Statuses

`RUNNING`, `PAUSED`, `AWAITING_APPROVAL`, `HANDED_OFF`, `COMPLETED`, `FAILED`. The last two are terminal.

### 7.2 Status Derived From Events

Status is a pure function of the event log. The server and the client MUST use the same reducer.

| Event | New status |
|---|---|
| SESSION_STARTED | RUNNING |
| PAUSED | PAUSED |
| RESUMED | RUNNING |
| APPROVAL_REQUESTED | AWAITING_APPROVAL |
| APPROVAL_DECIDED | RUNNING |
| HANDOFF_SUMMARY | HANDED_OFF |
| SESSION_COMPLETED | COMPLETED |
| SESSION_FAILED | FAILED |
| All other events | unchanged |

### 7.3 Allowed Transitions

```
RUNNING            --PAUSE-->           PAUSED
PAUSED             --RESUME-->          RUNNING
RUNNING / PAUSED   --HANDOFF-->         HANDED_OFF
HANDED_OFF         --RESUME (new controller)--> RUNNING
RUNNING            --risky TOOL_CALL--> AWAITING_APPROVAL
AWAITING_APPROVAL  --APPROVE / DENY / timeout--> RUNNING
RUNNING            --agent finishes-->  COMPLETED
any non-terminal   --budget / fatal error--> FAILED
```

Any other transition MUST be rejected with `INVALID_STATE`. After a terminal status, no commands are accepted (`SESSION_ENDED`).

---

## 8. Commands

A command is a request from a client. The server validates it, and if valid appends events and replies with `ack`.

### 8.1 Command Catalog

| Command | Payload | Minimum role | Must be controller | Allowed statuses | Resulting events |
|---|---|---|---|---|---|
| STEER | `{ instruction }` | OPERATOR | Yes | RUNNING, PAUSED | STEER |
| PAUSE | `{ reason? }` | OPERATOR | Yes | RUNNING | PAUSED |
| RESUME | `{ reason? }` | OPERATOR | Yes | PAUSED, HANDED_OFF | RESUMED |
| TAKE_CONTROL | `{ expectedControllerId, confirm }` | OPERATOR | No | RUNNING, PAUSED, AWAITING_APPROVAL, HANDED_OFF | CONTROL_TAKEN |
| HANDOFF | `{ toUserId, note? }` | OPERATOR | Yes | RUNNING, PAUSED | HANDOFF_SUMMARY, CONTROL_TAKEN |
| APPROVE | `{ approvalId, note? }` | APPROVER | No | AWAITING_APPROVAL | APPROVAL_DECIDED |
| DENY | `{ approvalId, note? }` | APPROVER | No | AWAITING_APPROVAL | APPROVAL_DECIDED |
| COMMENT | `{ text }` | OPERATOR | No | any non-terminal | HUMAN_MESSAGE |

Role order: VIEWER < OPERATOR < APPROVER < ADMIN. The "minimum role" column means that role or higher.

### 8.2 Validation Order

The server MUST validate in this order and return the first failure:

1. Authenticated connection (`UNAUTHENTICATED`).
2. Message shape and limits (`VALIDATION_FAILED`).
3. Rate limit (`RATE_LIMITED`).
4. Session exists and user's organization matches (`UNKNOWN_SESSION`).
5. Role is sufficient (`FORBIDDEN`).
6. Duplicate `commandId`: return the stored result (section 8.4).
7. Session not terminal (`SESSION_ENDED`).
8. Status allowed (`INVALID_STATE`).
9. Controller check (`NOT_CONTROLLER`, with `details.controllerId`).
10. Command-specific checks (`CONTROL_CHANGED`, `ALREADY_DECIDED`, `UNKNOWN_APPROVAL`).

### 8.3 Command-Specific Rules

- **TAKE_CONTROL:** `confirm` MUST be `true`. `expectedControllerId` is the controller the user saw (null if none). The server performs a compare-and-set on the lease; if the current controller differs, it returns `CONTROL_CHANGED` with `details.controllerId`, and the client re-asks the user.
- **HANDOFF:** `toUserId` MUST be an OPERATOR or higher in the same organization and different from the sender. The lease moves to the recipient immediately. The session becomes HANDED_OFF and the agent stays paused until the new controller sends RESUME.
- **STEER during PAUSED:** accepted and queued; the agent uses it after RESUME.
- **APPROVE / DENY:** see section 12.
- **COMMENT:** never reaches the agent.

### 8.4 Idempotency

- Every command carries a client-generated `id` (the `commandId`).
- The `commands` table stores `(sessionId, userId, commandId)` with the result, written in the same transaction as the events.
- A repeated `commandId` MUST NOT produce new events. The server returns the original `ack` (or the original error) with `"duplicate": true`.
- Clients SHOULD reuse the same `commandId` when retrying after a network failure.

---

## 9. WebSocket Protocol

### 9.1 Connection and Authentication

1. The client calls `POST /api/ws-ticket` with its access token and receives a one-time ticket (valid 30 seconds).
2. The client opens `wss://<host>/ws`. No credentials appear in the URL.
3. Within 5 seconds the client MUST send `auth` as its first message. Otherwise the server closes with code 4408.
4. The server consumes the ticket atomically (`GETDEL`). A reused, expired, or unknown ticket closes the connection with code 4401.

### 9.2 Client-to-Server Messages

```json
{ "op": "auth", "ticket": "<ticket>" }
{ "op": "subscribe", "sessionId": "<uuid>", "fromSeq": 12 }
{ "op": "unsubscribe", "sessionId": "<uuid>" }
{ "op": "command", "id": "c-77", "sessionId": "<uuid>", "type": "STEER",
  "payload": { "instruction": "Offer store credit instead." } }
{ "op": "pong" }
```

### 9.3 Server-to-Client Messages

```json
{ "op": "auth_ok", "protocol": 1, "user": { "id": "<uuid>", "role": "OPERATOR", "name": "Maya" } }
{ "op": "subscribed", "sessionId": "<uuid>", "lastSeq": 40 }
{ "op": "events", "sessionId": "<uuid>", "events": [ { "seq": 13 }, { "seq": 14 } ] }
{ "op": "event", "event": { "seq": 41 } }
{ "op": "caught_up", "sessionId": "<uuid>", "lastSeq": 40 }
{ "op": "ack", "id": "c-77", "sessionId": "<uuid>", "seqs": [42], "duplicate": false }
{ "op": "error", "id": "c-77", "code": "FORBIDDEN", "message": "Role cannot steer", "details": { } }
{ "op": "presence", "sessionId": "<uuid>", "users": [
  { "userId": "<uuid>", "name": "Maya", "role": "OPERATOR", "isController": true } ] }
{ "op": "ping" }
```

- `events` carries replay batches (maximum 500 events, in ascending `seq`). `event` carries one live event. Clients MUST handle both with the same `applyEvent` routine.
- `error` messages without an `id` refer to the connection itself (for example `RATE_LIMITED`).

### 9.4 Subscribe and Replay Algorithm

Server side, for each `subscribe(sessionId, fromSeq)`:

1. Verify the user belongs to the session's organization and has at least VIEWER role.
2. Reject with `INVALID_SEQUENCE` if `fromSeq` is greater than the session's `lastSeq`.
3. Register the connection as a live subscriber and start buffering any new events for it.
4. Read events with `seq > fromSeq` from PostgreSQL in batches of up to 500 and send them as `events` messages.
5. Send `subscribed` (before the first batch) and `caught_up` (after the last batch).
6. Flush the buffered live events with `seq` greater than the last sent `seq`, in order, then continue live.
7. For each connection track `lastSentSeq`. If a live event arrives with `seq > lastSentSeq + 1`, read the missing range from PostgreSQL first. Delivery order MUST always follow `seq`.

### 9.5 Client Apply Rules

The client keeps `lastSeq` per session (the highest `seq` applied).

| Situation | Action |
|---|---|
| `seq == lastSeq + 1` | Apply, set `lastSeq = seq` |
| `seq <= lastSeq` | Ignore (duplicate) |
| `seq > lastSeq + 1` | Gap: stop applying, buffer up to 1,000 later events, send `subscribe` with `fromSeq = lastSeq` |
| Three consecutive gaps | Reload the session through `GET /api/sessions/{id}/events?fromSeq=` and restart the subscription |
| After reconnect | Send `subscribe` with `fromSeq = lastSeq` for every open session |

Guarantee: delivery is at-least-once; application is exactly-once.

### 9.6 Client Connection State Machine

```
CONNECTING -> AUTHENTICATING -> REPLAYING -> LIVE
     ^                                         |
     +------------ reconnect (backoff) --------+
```

- The UI MUST display the state: connected, reconnecting, or replaying.
- Reconnect backoff: 0.5 s, 1 s, 2 s, 4 s, 8 s, then 15 s maximum, each with plus or minus 20 percent jitter. A fresh ticket is requested for every attempt.

### 9.7 Heartbeat

- The server sends `ping` every 15 seconds. The client replies `pong` within 10 seconds or the server closes the connection.
- A `pong` from the controller renews the control lease (section 11).

### 9.8 Presence

- Presence is ephemeral. It is NOT stored in the event log and does not consume `seq` numbers.
- The server sends `presence` when someone joins or leaves, debounced to at most once per 500 ms per session.
- A user disappears from presence 45 seconds after their last pong, or immediately when their last connection closes.

### 9.9 Close Codes

| Code | Meaning |
|---|---|
| 4401 | Unauthenticated (bad, expired, or reused ticket) |
| 4403 | Forbidden (user removed from the organization or role revoked) |
| 4408 | Authentication timeout |
| 4429 | Rate limited |
| 4500 | Internal server error (client should reconnect) |

---

## 10. Sequencing and Persistence

### 10.1 Database Schema (PostgreSQL)

```sql
CREATE TABLE sessions (
  id                 UUID PRIMARY KEY,
  organization_id    UUID NOT NULL,
  ticket_id          TEXT NOT NULL,
  agent_type         TEXT NOT NULL CHECK (agent_type IN ('SCRIPTED','LLM')),
  scenario           TEXT,
  status             TEXT NOT NULL CHECK (status IN
                       ('RUNNING','PAUSED','AWAITING_APPROVAL','HANDED_OFF','COMPLETED','FAILED')),
  controller_user_id UUID,
  last_seq           BIGINT NOT NULL DEFAULT 0,
  step_budget        INT NOT NULL,
  token_budget       INT NOT NULL,
  steps_used         INT NOT NULL DEFAULT 0,
  tokens_used        INT NOT NULL DEFAULT 0,
  created_by         UUID NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at           TIMESTAMPTZ
);

CREATE TABLE events (
  session_id UUID   NOT NULL REFERENCES sessions(id),
  seq        BIGINT NOT NULL,
  type       TEXT   NOT NULL,
  actor_kind TEXT   NOT NULL CHECK (actor_kind IN ('AGENT','USER','SYSTEM')),
  actor_id   TEXT   NOT NULL,
  actor_name TEXT,
  command_id TEXT,
  payload    JSONB  NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, seq)
);
CREATE INDEX events_type_time_idx  ON events (type, created_at);
CREATE INDEX events_actor_time_idx ON events (actor_id, created_at);

CREATE FUNCTION events_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'events are append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER events_no_update_delete
  BEFORE UPDATE OR DELETE ON events
  FOR EACH ROW EXECUTE FUNCTION events_immutable();

CREATE TABLE approvals (
  id           UUID PRIMARY KEY,
  session_id   UUID NOT NULL REFERENCES sessions(id),
  tool_call_id TEXT NOT NULL,
  status       TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','DENIED')),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL,
  decided_by   TEXT,
  decided_at   TIMESTAMPTZ,
  note         TEXT
);
CREATE UNIQUE INDEX approvals_session_tool_uq ON approvals (session_id, tool_call_id);
CREATE INDEX approvals_pending_idx ON approvals (status, expires_at);

CREATE TABLE commands (
  session_id UUID NOT NULL,
  user_id    UUID NOT NULL,
  command_id TEXT NOT NULL,
  result     JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, user_id, command_id)
);
```

*Note on V3 schema (`V3__tickets_and_orders.sql`):*
In Slice 2, the schema adds synthetic support tables `orders`, `tickets` (with composite foreign key `(organization_id, order_id) -> orders(organization_id, id)`), `ticket_notes`, and `refunds`. To enforce at most one active session per ticket within an organization, a partial unique index is added on sessions:
```sql
CREATE UNIQUE INDEX sessions_active_ticket_uq ON sessions (organization_id, ticket_id) WHERE ended_at IS NULL;
```

### 10.2 Assigning `seq` (gapless)

All writes for one session happen in one transaction that first locks the session row by incrementing the counter.

```sql
-- inside one transaction, per event to append:
UPDATE sessions
   SET last_seq = last_seq + 1
 WHERE id = :sessionId AND ended_at IS NULL
RETURNING last_seq;                      -- 0 rows => SESSION_ENDED

INSERT INTO events (session_id, seq, type, actor_kind, actor_id, actor_name, command_id, payload)
VALUES (:sessionId, :seq_from_returning, :type, :kind, :actorId, :actorName, :commandId, :payload);
```

- The row lock serializes concurrent writers, so two writers never receive the same `seq`, and a rolled-back transaction releases its number (no gaps).
- Terminal events (SESSION_COMPLETED, SESSION_FAILED) also set `ended_at` in the same transaction.
- A command's events, its `commands` row, any status change, and any `approvals` row commit together or not at all.

### 10.3 Live Fan-Out (Redis)

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `hg:s:{sessionId}:events` | Stream | Live fan-out between app instances; each event added with `XADD ... MAXLEN ~ 1000` after the database commit | trimmed by length |
| `hg:s:{sessionId}:lease` | String | Current controller user id | 30 s, renewed |
| `hg:s:{sessionId}:presence` | Sorted set | userId scored by last-seen epoch seconds | pruned at 45 s |
| `hg:ticket:{ticket}` | String (JSON) | One-time WebSocket ticket (`GETDEL`) | 30 s |

- PostgreSQL is the source of truth. The Redis stream order is advisory; per-connection delivery order is enforced by `seq` (section 9.4, step 7).
- If Redis is lost, nothing durable is lost: events and approvals are in PostgreSQL, presence rebuilds from pongs, and the lease is rebuilt from the last CONTROL_TAKEN or CONTROL_RELEASED event with a fresh TTL.

---

## 11. Control Lease

### 11.1 Rules

- The creator of a session becomes the controller (CONTROL_TAKEN with `via = SESSION_START`).
- The lease key holds the controller's user id with a 30-second TTL.
- It is renewed on every `pong` from the controller and on every accepted command from the controller.
- When the lease expires, the server appends CONTROL_RELEASED (`reason = LEASE_EXPIRED`). The session keeps its current status; commands that require control are rejected until someone takes control.

### 11.2 Compare-and-Set (Lua)

```lua
-- KEYS[1] = lease key
-- ARGV[1] = expected owner ("" when no controller is expected)
-- ARGV[2] = new owner
-- ARGV[3] = ttl seconds
local cur = redis.call('GET', KEYS[1])
if (not cur and ARGV[1] == '') or cur == ARGV[1] then
  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
  return 1
end
return 0
```

A return of 0 maps to `CONTROL_CHANGED`. When two users request control at the same instant with the same `expectedControllerId`, exactly one script run returns 1 (requirement R4).

### 11.3 Renewal

```lua
-- KEYS[1] = lease key, ARGV[1] = owner, ARGV[2] = ttl seconds
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return 0
```

---

## 12. Approval Flow

### 12.1 Creating an Approval

1. The agent emits TOOL_CALL with `risky: true`.
2. In one transaction the server inserts an `approvals` row (`PENDING`, `expires_at = now + approval timeout`) and appends APPROVAL_REQUESTED.
3. The agent is suspended. The tool is NOT executed.
4. Default approval timeout: 10 minutes (configurable; see prd.md OQ-5).

### 12.2 Deciding (exactly one winner)

```sql
UPDATE approvals
   SET status = :decision, decided_by = :userId, decided_at = now(), note = :note
 WHERE id = :approvalId AND status = 'PENDING'
RETURNING id;
```

- 1 row returned: append APPROVAL_DECIDED in the same transaction and respond `ack`.
- 0 rows returned: respond `ALREADY_DECIDED` with `details` containing the stored decision, `decidedBy`, and `decidedAt`. No event is written.

### 12.3 After the Decision

| Decision | Agent behavior |
|---|---|
| APPROVED | The tool executes; TOOL_RESULT follows; the run continues |
| DENIED (human) | The agent receives "action denied" with the note, picks an alternative, and continues (prd.md OQ-2) |
| DENIED (timeout) | Same as a human denial, with `decidedBy = TIMEOUT` and a SYSTEM actor |

A scheduled job scans `approvals` where `status = 'PENDING' AND expires_at < now()` and decides them with the same conditional update, so a late human click and the timeout cannot both succeed.

### 12.4 Risk Configuration

| Tool | Risky when |
|---|---|
| issue_refund | `amount` is greater than `HANDOFF_REFUND_LIMIT` (default 2000 INR, configurable) |
| close_ticket | never (default) |
| add_note | never |
| lookup_order | never |

---

## 13. Agent Contract

### 13.1 Interface

Both agents implement one interface so they are interchangeable (FR-AGT-1).

| Aspect | Rule |
|---|---|
| Input | The session's event log up to the current `seq` plus any queued STEER instructions |
| Output | A stream of: text chunk, tool call, or completion |
| State | Derived only from the event log. No hidden in-memory state is required to continue |
| Recovery | After a restart, the runtime rebuilds the agent context from the log and appends AGENT_RECOVERED (FR-AGT-4) |
| Suspension | After a risky TOOL_CALL the agent does nothing until APPROVAL_DECIDED exists in the log |
| Untrusted input | Ticket text and tool results are data, never instructions (FR-AGT-5) |

### 13.2 Tool Allowlist and Arguments

| Tool | Arguments |
|---|---|
| lookup_order | `{ orderId: string }` |
| issue_refund | `{ orderId: string, amount: number, currency: string, reason: string }` |
| add_note | `{ ticketId: string, text: string }` |
| close_ticket | `{ ticketId: string, resolution: string }` |

Any tool name outside this list MUST be rejected by the runtime: no tool executes, and an ERROR event with code `TOOL_FAILED` is appended.

### 13.3 Budgets

- A **step** is one TOOL_CALL or one final AGENT_TEXT message.
- Defaults: `stepBudget = 20`, `tokenBudget = 20000` (scripted agents report 0 tokens).
- When a budget is reached the runtime appends BUDGET_EXCEEDED, then SESSION_FAILED with reason `BUDGET_EXCEEDED`.
- A global daily LLM spending cap disables LLM sessions; creating one returns `LLM_DISABLED`, and a running one fails with BUDGET_EXCEEDED (`kind = DAILY_CAP`).

### 13.4 Scripted Scenarios (deterministic)

| Scenario | Behavior | Used to test |
|---|---|---|
| SIMPLE_LOOKUP | lookup_order (<orderId>), add_note (<ticketId>), close_ticket (<ticketId>), no approval | Basic ordering and streaming (seq 1..13) |
| REFUND_APPROVAL | lookup_order, then issue_refund above the limit, then wait for approval | Approval gates, race on decision, audit |
| OFF_TRACK | Proposes a wrong resolution; if a STEER arrives before the next step, switches to the steered resolution | Steering |
| BUDGET_EXHAUST | Repeats lookup_order until the step budget ends | Budget failure |
| LONG_STREAM | Emits 2,000 AGENT_TEXT chunks followed by SESSION_COMPLETED | Replay and load tests (seq 1..2003) |

**Exact Event Sequences for Slice 2 Scenarios:**

The scripted agent takes `<ticketId>` and the linked `<orderId>` dynamically from the session's ticket (worked example: ticket `T-101` linked to order `8841`). If the ticket has no linked order, `SIMPLE_LOOKUP` appends an `ERROR` event (`code = TOOL_FAILED`, `recoverable = false`) followed by `SESSION_FAILED` (`reason = AGENT_ERROR`). `LONG_STREAM` works for any ticket and does not close it.

- **SIMPLE_LOOKUP (13 events with linked order):**
  1. `SESSION_STARTED` (ticketId `<ticketId>`, scenario "SIMPLE_LOOKUP", stepBudget 20, tokenBudget 20000, createdBy user)
  2. `CONTROL_TAKEN` (fromUserId null, toUserId creator, via "SESSION_START")
  3. `AGENT_TEXT` ("Looking up order <orderId>...", final: true)
  4. `TOOL_CALL` (toolCallId "tc-1", tool "lookup_order", args `{"orderId":"<orderId>"}`, risky: false)
  5. `TOOL_RESULT` (toolCallId "tc-1", ok: true, result order object)
  6. `AGENT_TEXT` ("Order <orderId> is delivered. Adding note to ticket...", final: true)
  7. `TOOL_CALL` (toolCallId "tc-2", tool "add_note", args `{"ticketId":"<ticketId>","text":"Verified order <orderId> status: DELIVERED."}`, risky: false)
  8. `TOOL_RESULT` (toolCallId "tc-2", ok: true, result `{"ticketId":"<ticketId>","noteAdded":true}`)
  9. `AGENT_TEXT` ("Closing ticket <ticketId>...", final: true)
  10. `TOOL_CALL` (toolCallId "tc-3", tool "close_ticket", args `{"ticketId":"<ticketId>","resolution":"Informed customer that order <orderId> was delivered."}`, risky: false)
  11. `TOOL_RESULT` (toolCallId "tc-3", ok: true, result `{"ticketId":"<ticketId>","status":"CLOSED"}`)
  12. `AGENT_TEXT` ("Ticket resolved and closed.", final: true)
  13. `SESSION_COMPLETED` (outcome "RESOLVED", summary "Looked up order <orderId>, added verification note, and closed ticket.")

- **SIMPLE_LOOKUP Failure Sequence (ticket with no linked order):**
  1. `SESSION_STARTED` (ticketId `<ticketId>`, scenario "SIMPLE_LOOKUP", stepBudget 20, tokenBudget 20000, createdBy user)
  2. `CONTROL_TAKEN` (fromUserId null, toUserId creator, via "SESSION_START")
  3. `ERROR` (code "TOOL_FAILED", message "No linked order for ticket <ticketId>", recoverable: false)
  4. `SESSION_FAILED` (reason "AGENT_ERROR", message "Failed to perform lookup: ticket has no linked order.")

- **LONG_STREAM (2003 events):**
  1. `SESSION_STARTED` (ticketId `<ticketId>`, scenario "LONG_STREAM", stepBudget 20, tokenBudget 20000, createdBy user)
  2. `CONTROL_TAKEN` (fromUserId null, toUserId creator, via "SESSION_START")
  3. to 2002. `AGENT_TEXT` (messageId "m-1", text "Chunk N of 2000 for ticket <ticketId>...", final: false for 1..1999, final: true for 2000th / seq 2002)
  2003. `SESSION_COMPLETED` (outcome "COMPLETED", summary "Completed 2000 stream chunks.")

Scripted agents use fixed text, fixed ids (`tc-1`, `tc-2`, ...), and no randomness. Delays between steps are configurable and are set to 0 in automated tests.

---

## 14. REST API

Base path `/api`. JSON bodies. Authenticated endpoints require `Authorization: Bearer <accessToken>`. Access tokens last 15 minutes; refresh tokens last 7 days and rotate on use.

### 14.1 Endpoint Summary

| Method and path | Purpose | Minimum role |
|---|---|---|
| POST /auth/register | Create account and organization membership | public |
| POST /auth/login | Log in | public |
| POST /auth/refresh | Rotate tokens | refresh token |
| POST /auth/logout | Revoke refresh token | any |
| POST /ws-ticket | One-time WebSocket ticket | any logged-in |
| GET /tickets | Seeded fake tickets for creating sessions | VIEWER |
| GET /sessions | List sessions | VIEWER |
| POST /sessions | Create a session | OPERATOR |
| GET /sessions/{id} | Session snapshot | VIEWER |
| GET /sessions/{id}/events?fromSeq=&limit= | Read the event log | VIEWER |
| GET /approvals?status= | Approval queue | APPROVER |
| GET /approvals/{id} | One approval | APPROVER |
| GET /audit | Filtered audit trail | VIEWER |
| GET /metrics?from=&to= | Oversight metrics | ADMIN |
| GET /org/members | List members | ADMIN |
| PATCH /org/members/{userId} | Change a role | ADMIN |

### 14.2 Key Contracts

**POST /auth/register**
```json
// request
{ "email": "maya@example.com", "password": "Password123!", "displayName": "Maya", "organizationName": "Acme Support" }
// 201 response
{ "accessToken": "<jwt>", "refreshToken": "<opaque>", "expiresInSeconds": 900,
  "user": { "id": "<uuid>", "name": "Maya", "role": "ADMIN", "organizationId": "<uuid>" } }
```

**POST /auth/login**
```json
// request
{ "email": "maya@example.com", "password": "********" }
// 200 response
{ "accessToken": "<jwt>", "refreshToken": "<opaque>", "expiresInSeconds": 900,
  "user": { "id": "<uuid>", "name": "Maya", "role": "OPERATOR", "organizationId": "<uuid>" } }
```

**POST /auth/refresh**
```json
// request
{ "refreshToken": "<opaque>" }
// 200 response
{ "accessToken": "<jwt>", "refreshToken": "<opaque>", "expiresInSeconds": 900,
  "user": { "id": "<uuid>", "name": "Maya", "role": "OPERATOR", "organizationId": "<uuid>" } }
```

**POST /auth/logout**
```json
// request
{ "refreshToken": "<opaque>" }
// 204 No Content
```

**POST /ws-ticket**
```json
// 200 response
{ "ticket": "<opaque>", "expiresInSeconds": 30 }
```

**POST /sessions**
```json
// request
{ "ticketId": "T-102", "agentType": "SCRIPTED", "scenario": "REFUND_APPROVAL" }
// 201 response
{ "id": "<uuid>", "ticketId": "T-102", "agentType": "SCRIPTED", "scenario": "REFUND_APPROVAL",
  "status": "RUNNING", "controllerUserId": "<uuid>", "lastSeq": 2,
  "stepBudget": 20, "tokenBudget": 20000, "createdAt": "2026-10-03T10:15:00.000Z" }
```

**GET /sessions/{id}/events?fromSeq=12&limit=500**
```json
{ "sessionId": "<uuid>", "events": [ { "seq": 13 } ], "lastSeq": 40, "hasMore": false }
```
Returns events with `seq > fromSeq` in ascending order. `limit` defaults to 200 and has a maximum of 500.

**GET /approvals?status=PENDING**
```json
{ "items": [ { "id": "<uuid>", "sessionId": "<uuid>", "toolCallId": "tc-2", "tool": "issue_refund",
    "summary": "Refund 4200 INR on order 8841", "status": "PENDING",
    "requestedAt": "2026-10-03T10:15:31.000Z", "expiresAt": "2026-10-03T10:25:31.000Z" } ],
  "nextCursor": null }
```

**GET /audit**
Query parameters: `sessionId`, `userId`, `type` (repeatable), `from`, `to`, `cursor`, `limit` (default 50, maximum 200).
```json
{ "items": [ { "id": "<opaque>", "scope": "SESSION", "sessionId": "<uuid>", "seq": 7,
    "type": "APPROVAL_DECIDED", "actor": { "kind": "USER", "id": "<uuid>", "name": "Ravi" },
    "payload": { "decision": "APPROVED" }, "createdAt": "2026-10-03T10:16:02.000Z" } ],
  "nextCursor": "<opaque or null>" }
```
Items with `scope = ORG` (for example `ROLE_CHANGED`) have no `sessionId` or `seq`. Role changes made through PATCH /org/members/{userId} are stored as organization audit entries (FR-AUTH-4).

**GET /metrics?from=&to=**
```json
{ "period": { "from": "2026-09-27T00:00:00.000Z", "to": "2026-10-03T23:59:59.999Z" },
  "sessions": 42, "handoffs": 6, "interventions": 15, "interventionRatePct": 28.6,
  "approvals": { "requested": 19, "approved": 14, "denied": 5, "timedOut": 1, "denialRatePct": 26.3,
                 "latencyMs": { "p50": 18000, "p95": 95000 } } }
```

**PATCH /org/members/{userId}**
```json
{ "role": "APPROVER" }
```

---

## 15. Error Model

### 15.1 REST Error Body

```json
{ "error": { "code": "FORBIDDEN", "message": "Role cannot steer",
             "details": { }, "correlationId": "7c1f..." } }
```

### 15.2 Error Codes

| Code | HTTP | Meaning |
|---|---|---|
| UNAUTHENTICATED | 401 | Missing or invalid credentials |
| TICKET_INVALID | 401 | WebSocket ticket unknown, expired, or reused |
| FORBIDDEN | 403 | Role does not allow the action |
| NOT_CONTROLLER | 403 | The action requires being the controller; `details.controllerId` is set |
| UNKNOWN_SESSION | 404 | Session does not exist or belongs to another organization |
| UNKNOWN_APPROVAL | 404 | Approval does not exist in this session |
| VALIDATION_FAILED | 400 | Payload failed validation; `details.fields` lists problems |
| INVALID_SEQUENCE | 400 | `fromSeq` is greater than the session's last `seq` |
| INVALID_STATE | 409 | The command is not allowed in the current status |
| SESSION_ENDED | 409 | The session is COMPLETED or FAILED |
| CONTROL_CHANGED | 409 | The controller changed since the client last saw it; `details.controllerId` is set |
| ALREADY_DECIDED | 409 | The approval already has a decision; `details` holds it |
| LLM_DISABLED | 409 | The daily LLM spending cap is reached |
| RATE_LIMITED | 429 | Too many requests; `details.retryAfterSeconds` is set |
| INTERNAL | 500 | Unexpected failure (never reveals internals) |

Over WebSocket, the same codes appear in `error` messages. Failed commands never append events (except events that the system produces on its own, such as ERROR).

---

## 16. Limits

| Limit | Value |
|---|---|
| WebSocket message size | 16 KB |
| Event payload size | 64 KB |
| Steer instruction | 2,000 characters |
| Human comment | 1,000 characters |
| Pause or resume reason, approval note, handoff note | 500 characters |
| Replay batch | 500 events |
| Commands per user | 20 per 10 seconds |
| Login attempts | 5 per minute per IP address |
| WebSocket tickets | 10 per minute per user |
| Subscribers per session | 200 (performance target is validated at 50) |
| Client gap buffer | 1,000 events |

---

## 17. Metric Definitions (computed from events)

| Metric | Definition |
|---|---|
| Sessions | Count of SESSION_STARTED in the period |
| Hand-offs | Count of HANDOFF_SUMMARY in the period |
| Intervention | A STEER event, a PAUSED event by a USER, or a CONTROL_TAKEN event with `via = TAKE_CONTROL` |
| Interventions | Count of intervention events in the period |
| Intervention rate | Sessions with at least one intervention divided by sessions started, as a percentage |
| Approval latency | `APPROVAL_DECIDED.createdAt` minus `APPROVAL_REQUESTED.createdAt`, for decisions with `decidedBy = HUMAN`; report median (p50) and p95 |
| Denial rate | Denied decisions divided by all decided approvals (including timeouts) |
| Timed-out approvals | APPROVAL_DECIDED events with `decidedBy = TIMEOUT` |

---

## 18. TypeScript Types (frontend contract)

```ts
export type Role = 'VIEWER' | 'OPERATOR' | 'APPROVER' | 'ADMIN';
export type SessionStatus =
  | 'RUNNING' | 'PAUSED' | 'AWAITING_APPROVAL' | 'HANDED_OFF' | 'COMPLETED' | 'FAILED';

export interface Actor { kind: 'AGENT' | 'USER' | 'SYSTEM'; id: string; name?: string | null }

interface BaseEvent<T extends string, P> {
  sessionId: string;
  seq: number;
  type: T;
  actor: Actor;
  commandId: string | null;
  payload: P;
  createdAt: string;
}

export type HandoffEvent =
  | BaseEvent<'SESSION_STARTED', { ticketId: string; agentType: 'SCRIPTED' | 'LLM';
      scenario?: string | null; stepBudget: number; tokenBudget: number; createdBy: string }>
  | BaseEvent<'AGENT_TEXT', { messageId: string; text: string; final: boolean }>
  | BaseEvent<'TOOL_CALL', { toolCallId: string; tool: string; args: Record<string, unknown>;
      risky: boolean; riskReason: string | null }>
  | BaseEvent<'TOOL_RESULT', { toolCallId: string; ok: boolean;
      result: Record<string, unknown> | null; error: string | null }>
  | BaseEvent<'STEER', { instruction: string }>
  | BaseEvent<'HUMAN_MESSAGE', { text: string }>
  | BaseEvent<'PAUSED', { reason?: string }>
  | BaseEvent<'RESUMED', { reason?: string }>
  | BaseEvent<'APPROVAL_REQUESTED', { approvalId: string; toolCallId: string; tool: string;
      args: Record<string, unknown>; summary: string; expiresAt: string }>
  | BaseEvent<'APPROVAL_DECIDED', { approvalId: string; toolCallId: string;
      decision: 'APPROVED' | 'DENIED'; decidedBy: 'HUMAN' | 'TIMEOUT'; note?: string }>
  | BaseEvent<'CONTROL_TAKEN', { fromUserId: string | null; toUserId: string;
      via: 'SESSION_START' | 'TAKE_CONTROL' | 'HANDOFF' }>
  | BaseEvent<'CONTROL_RELEASED', { userId: string; reason: 'LEASE_EXPIRED' }>
  | BaseEvent<'HANDOFF_SUMMARY', { fromUserId: string; toUserId: string; note?: string; summary: string }>
  | BaseEvent<'BUDGET_EXCEEDED', { kind: 'STEPS' | 'TOKENS' | 'DAILY_CAP'; limit: number; used: number }>
  | BaseEvent<'AGENT_RECOVERED', { resumedAfterSeq: number; reason: string }>
  | BaseEvent<'ERROR', { code: string; message: string; recoverable: boolean }>
  | BaseEvent<'SESSION_COMPLETED', { outcome: string; summary: string }>
  | BaseEvent<'SESSION_FAILED', { reason: 'BUDGET_EXCEEDED' | 'AGENT_ERROR' | 'INTERNAL'; message: string }>;

export type CommandType =
  | 'STEER' | 'PAUSE' | 'RESUME' | 'TAKE_CONTROL' | 'HANDOFF' | 'APPROVE' | 'DENY' | 'COMMENT';

export type ClientMessage =
  | { op: 'auth'; ticket: string }
  | { op: 'subscribe'; sessionId: string; fromSeq: number }
  | { op: 'unsubscribe'; sessionId: string }
  | { op: 'command'; id: string; sessionId: string; type: CommandType; payload: Record<string, unknown> }
  | { op: 'pong' };

export type ServerMessage =
  | { op: 'auth_ok'; protocol: number; user: { id: string; role: Role; name: string } }
  | { op: 'subscribed'; sessionId: string; lastSeq: number }
  | { op: 'events'; sessionId: string; events: HandoffEvent[] }
  | { op: 'event'; event: HandoffEvent }
  | { op: 'caught_up'; sessionId: string; lastSeq: number }
  | { op: 'ack'; id: string; sessionId: string; seqs: number[]; duplicate: boolean }
  | { op: 'error'; id?: string; code: string; message: string; details?: Record<string, unknown> }
  | { op: 'presence'; sessionId: string; users: { userId: string; name: string; role: Role; isController: boolean }[] }
  | { op: 'ping' };
```

---

## 19. Worked Examples

### 19.1 REFUND_APPROVAL (happy path)

| seq | type | actor | key payload |
|---|---|---|---|
| 1 | SESSION_STARTED | USER maya | ticketId T-102, scenario REFUND_APPROVAL |
| 2 | CONTROL_TAKEN | USER maya | via SESSION_START, toUserId maya |
| 3 | AGENT_TEXT | AGENT | "Checking order 8841..." |
| 4 | TOOL_CALL | AGENT | lookup_order, risky false |
| 5 | TOOL_RESULT | SYSTEM | ok true |
| 6 | AGENT_TEXT | AGENT | "Order is eligible. Requesting a refund." |
| 7 | TOOL_CALL | AGENT | issue_refund amount 4200, risky true |
| 8 | APPROVAL_REQUESTED | SYSTEM | approvalId A1, status becomes AWAITING_APPROVAL |
| 9 | APPROVAL_DECIDED | USER ravi | APPROVED, decidedBy HUMAN |
| 10 | TOOL_RESULT | SYSTEM | ok true, refundId R-5521 |
| 11 | SESSION_COMPLETED | AGENT | outcome REFUNDED |

### 19.2 OFF_TRACK with a steer

| seq | type | actor | key payload |
|---|---|---|---|
| 1 to 2 | SESSION_STARTED, CONTROL_TAKEN | maya | as above |
| 3 | AGENT_TEXT | AGENT | "I will reject this refund request." |
| 4 | PAUSED | USER maya | reason "Wrong resolution" |
| 5 | STEER | USER maya | "Offer store credit instead." |
| 6 | RESUMED | USER maya | |
| 7 | AGENT_TEXT | AGENT | "Offering store credit of 4200 INR." |
| 8 | SESSION_COMPLETED | AGENT | outcome STORE_CREDIT |

### 19.3 Reconnect and replay

```
client: lastSeq = 7 for session S
network drops
server: appends events 8, 9, 10 (client misses them)
client: POST /api/ws-ticket
client: open /ws, send {"op":"auth","ticket":"T2"}
server: {"op":"auth_ok", ...}
client: {"op":"subscribe","sessionId":"S","fromSeq":7}
server: {"op":"subscribed","sessionId":"S","lastSeq":10}
server: {"op":"events","sessionId":"S","events":[ seq 8, seq 9, seq 10 ]}
server: {"op":"caught_up","sessionId":"S","lastSeq":10}
server: {"op":"event","event":{ "seq": 11 }}        // live again
client applies 8, 9, 10, 11 in order; lastSeq = 11; no gaps, no duplicates
```

### 19.4 Approval race (two approvers click at the same time)

```
ravi:  {"op":"command","id":"c-1","sessionId":"S","type":"APPROVE","payload":{"approvalId":"A1"}}
sunil: {"op":"command","id":"c-9","sessionId":"S","type":"APPROVE","payload":{"approvalId":"A1"}}

database: exactly one UPDATE ... WHERE status='PENDING' returns a row

ravi  <- {"op":"ack","id":"c-1","sessionId":"S","seqs":[9],"duplicate":false}
sunil <- {"op":"error","id":"c-9","code":"ALREADY_DECIDED","message":"Already decided",
          "details":{"decision":"APPROVED","decidedBy":"ravi","decidedAt":"2026-10-03T10:16:02.000Z"}}
everyone <- {"op":"event","event":{ "seq": 9, "type": "APPROVAL_DECIDED" }}   // exactly one
```

### 19.5 Take-control race

```
maya is controller. Ravi and Sunil both saw "controller = maya".
ravi:  TAKE_CONTROL {"expectedControllerId":"maya","confirm":true}
sunil: TAKE_CONTROL {"expectedControllerId":"maya","confirm":true}

lease compare-and-set: first script run returns 1, second returns 0

ravi  <- ack (seq of CONTROL_TAKEN, fromUserId maya, toUserId ravi)
sunil <- error CONTROL_CHANGED, details {"controllerId":"ravi"}   // UI asks again
```

### 19.6 Duplicate command (network retry)

```
client sends command id "c-5" (STEER), connection drops before ack
client reconnects and resends the same command id "c-5"
server: finds (sessionId, userId, "c-5") in commands table
server: {"op":"ack","id":"c-5","sessionId":"S","seqs":[12],"duplicate":true}
log still contains exactly one STEER event
```

---

## 20. Conformance Tests

Every row below MUST have an automated test before the MVP is considered complete. These map to prd.md section 21.1 and section 26.

| ID | Test | Rule | Expected result |
|---|---|---|---|
| T1 | 50 concurrent appends to one session | R1 | `seq` values are 1 to N with no gaps and no duplicates |
| T2 | Update or delete an event row | R1 | Database rejects it |
| T3 | Random disconnects during LONG_STREAM across 100 sessions | R2 | Client list equals server log; zero gaps, zero duplicates |
| T4 | Two or more simultaneous APPROVE or DENY, repeated 1,000 times | R3 | Exactly one APPROVAL_DECIDED each run; others get ALREADY_DECIDED |
| T5 | Approval timeout racing a human click | R3 | Exactly one decision |
| T6 | Two simultaneous TAKE_CONTROL with the same `expectedControllerId` | R4 | One CONTROL_TAKEN; the other gets CONTROL_CHANGED |
| T7 | Lease expiry | R4 | CONTROL_RELEASED appended; control-required commands return NOT_CONTROLLER |
| T8 | Role by command matrix (every role, every command) | R5 | Results match section 8.1 exactly |
| T9 | Command from a user of another organization | R5 | UNKNOWN_SESSION |
| T10 | REFUND_APPROVAL scenario | R6 | Tool does not run until approval; TOOL_RESULT follows APPROVAL_DECIDED |
| T11 | Tool name outside the allowlist | R6 | No execution; ERROR event with code TOOL_FAILED |
| T12 | Audit completeness: replay a scripted run and compare commands to events | R7 | Every human action and tool call is present |
| T13 | Duplicate `commandId` | idempotency | Single effect; `duplicate: true` on the second ack |
| T14 | BUDGET_EXHAUST scenario | budgets | BUDGET_EXCEEDED then SESSION_FAILED; no further commands accepted |
| T15 | Server restart mid-session | FR-AGT-4 | AGENT_RECOVERED appended; run continues from the last logged event |
| T16 | WebSocket ticket reused or expired | auth | Close code 4401 |
| T17 | Subscribe with `fromSeq` greater than `lastSeq` | protocol | INVALID_SEQUENCE |
| T18 | Client receives out-of-order events | R2 | Gap detected and re-subscribed; final state is correct |
| T19 | Status reducer property test | state machine | Server status equals client-derived status for random valid event sequences |

Tests MUST be deterministic: no fixed sleeps, use awaits and latches, use scripted agents with zero delay, and use real PostgreSQL and Redis through Testcontainers.

---

## 21. Versioning and Change Control

- The protocol version is 1 and is reported in `auth_ok.protocol`.
- Allowed without a version bump: adding optional payload fields and adding new event types (clients ignore unknown fields and render unknown types generically).
- Requires a version bump: removing or renaming fields, changing a field's meaning or type, changing ordering or replay rules.
- A change to this file requires: the product owner's approval, updated TypeScript types in section 18, updated fixtures for the affected worked examples, and new or updated conformance tests.
- Stored events are never rewritten when the schema evolves; readers handle older shapes.

---

## 22. Open Items

These link to the open questions in prd.md section 24.

| ID | Item | Current decision in this document |
|---|---|---|
| OQ-1 | Default risk threshold for refunds | 2000 INR, configurable (section 12.4) |
| OQ-2 | Behavior after a denied approval | The agent continues with an alternative (section 12.3) |
| OQ-4 | Hash-chained audit log | Not included; a `prev_hash` column can be added later without a protocol version bump |
| OQ-5 | Approval timeout | 10 minutes, configurable (section 12.1) |
```
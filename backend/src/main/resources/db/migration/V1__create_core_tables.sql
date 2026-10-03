-- V1: Core tables from docs/events.md section 10.1
-- These four tables support sessions, the append-only event log,
-- approval gates, and command idempotency.

-- ============================================================
-- sessions: one row per agent run on a ticket
-- ============================================================
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

-- ============================================================
-- events: append-only log, one row per thing that happened
-- The pair (session_id, seq) is the primary key — it is unique
-- and never reused.  See R1 in AGENTS.md.
-- ============================================================
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

-- Trigger: events are append-only — UPDATE and DELETE are forbidden.
CREATE FUNCTION events_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'events are append-only'; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER events_no_update_delete
    BEFORE UPDATE OR DELETE ON events
    FOR EACH ROW EXECUTE FUNCTION events_immutable();

-- ============================================================
-- approvals: one row per risky tool-call approval request
-- The conditional UPDATE (WHERE status = 'PENDING') enforces
-- the "exactly one decision" rule (R3).
-- ============================================================
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

-- ============================================================
-- commands: stores each command's result for idempotency.
-- If a client retries the same commandId, the server returns
-- the stored result instead of executing the command again.
-- ============================================================
CREATE TABLE commands (
    session_id UUID NOT NULL,
    user_id    UUID NOT NULL,
    command_id TEXT NOT NULL,
    result     JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, user_id, command_id)
);

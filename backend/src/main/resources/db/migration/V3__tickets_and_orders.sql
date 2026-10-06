-- V3: Synthetic tickets and orders with composite primary keys for strict tenant isolation,
-- plus a partial unique index ensuring at most one active session per ticket.

-- ============================================================
-- orders: customer orders scoped to organizations
-- ============================================================
CREATE TABLE orders (
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    id              TEXT NOT NULL,
    customer_name   TEXT NOT NULL,
    customer_email  TEXT NOT NULL,
    items           JSONB NOT NULL,
    total_amount    INT NOT NULL,          -- In minor currency units or whole INR (e.g. 4200)
    currency        TEXT NOT NULL DEFAULT 'INR',
    status          TEXT NOT NULL CHECK (status IN ('DELIVERED', 'SHIPPED', 'CANCELLED', 'REFUNDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id)
);

CREATE INDEX orders_org_status_idx ON orders (organization_id, status);

-- ============================================================
-- tickets: customer support tickets scoped to organizations
-- ============================================================
CREATE TABLE tickets (
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    id              TEXT NOT NULL,
    customer_name   TEXT NOT NULL,
    customer_email  TEXT NOT NULL,
    subject         TEXT NOT NULL,
    description     TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('OPEN', 'PENDING', 'CLOSED')),
    order_id        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, order_id) REFERENCES orders(organization_id, id)
);

CREATE INDEX tickets_org_status_idx ON tickets (organization_id, status);

-- ============================================================
-- ticket_notes: notes added to tickets by tools (add_note)
-- ============================================================
CREATE TABLE ticket_notes (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    ticket_id       TEXT NOT NULL,
    author          TEXT NOT NULL,
    text            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, ticket_id) REFERENCES tickets(organization_id, id) ON DELETE CASCADE
);

-- ============================================================
-- refunds: records of refunds issued against orders (issue_refund)
-- ============================================================
CREATE TABLE refunds (
    id              TEXT PRIMARY KEY,
    organization_id UUID NOT NULL,
    order_id        TEXT NOT NULL,
    amount          INT NOT NULL,
    currency        TEXT NOT NULL,
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, order_id) REFERENCES orders(organization_id, id)
);

-- ============================================================
-- Partial unique index on sessions:
-- At most ONE active (non-ended) session per ticket within an organization
-- ============================================================
CREATE UNIQUE INDEX sessions_active_ticket_uq
    ON sessions (organization_id, ticket_id)
    WHERE ended_at IS NULL;

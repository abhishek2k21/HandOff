-- V2: Authentication and authorization tables
-- Creates organizations, users, memberships, and refresh_tokens.
-- Adds foreign key constraints to V1 tables sessions and commands.

-- ============================================================
-- organizations: tenant boundary
-- ============================================================
CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- users: authentication credentials and display details
-- ============================================================
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Case-insensitive unique email index
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

-- ============================================================
-- memberships: assigns exactly one role per user within an organization
-- Role hierarchy: VIEWER < OPERATOR < APPROVER < ADMIN
-- ============================================================
CREATE TABLE memberships (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role            TEXT NOT NULL CHECK (role IN ('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX memberships_org_idx ON memberships (organization_id);

-- ============================================================
-- refresh_tokens: hashed, rotating, and revocable session tokens
-- Stores SHA-256 hash of the opaque token string
-- ============================================================
CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX refresh_tokens_user_revoked_idx ON refresh_tokens (user_id, revoked);

-- ============================================================
-- Add foreign key constraints to V1 tables
-- ============================================================
ALTER TABLE sessions
    ADD CONSTRAINT fk_sessions_organization
    FOREIGN KEY (organization_id) REFERENCES organizations(id);

ALTER TABLE sessions
    ADD CONSTRAINT fk_sessions_created_by
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE sessions
    ADD CONSTRAINT fk_sessions_controller
    FOREIGN KEY (controller_user_id) REFERENCES users(id);

ALTER TABLE commands
    ADD CONSTRAINT fk_commands_user
    FOREIGN KEY (user_id) REFERENCES users(id);

package com.handoff.auth;

/**
 * User roles within an organization.
 * Hierarchy: VIEWER < OPERATOR < APPROVER < ADMIN.
 */
public enum Role {
    VIEWER,
    OPERATOR,
    APPROVER,
    ADMIN;

    public boolean isAtLeast(Role required) {
        return this.ordinal() >= required.ordinal();
    }
}

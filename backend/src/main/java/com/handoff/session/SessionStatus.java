package com.handoff.session;

public enum SessionStatus {
    RUNNING,
    PAUSED,
    AWAITING_APPROVAL,
    HANDED_OFF,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}

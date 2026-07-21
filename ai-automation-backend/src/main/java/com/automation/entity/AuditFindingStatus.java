package com.automation.entity;

public enum AuditFindingStatus {
    OPEN, AWAITING_VERIFICATION, RESOLVED, ACCEPTED_RISK, DISMISSED;

    public boolean isTerminal() {
        return this == RESOLVED || this == ACCEPTED_RISK || this == DISMISSED;
    }
}

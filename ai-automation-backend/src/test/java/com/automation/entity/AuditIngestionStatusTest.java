package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditIngestionStatusTest {

    @Test
    void values_areValidAndInvalid() {
        assertThat(AuditIngestionStatus.values())
                .containsExactly(AuditIngestionStatus.VALID, AuditIngestionStatus.INVALID);
    }

    @Test
    void valueOf_roundTripsThePersistedName() {
        // Als EnumType.STRING gespeichert: der Name ist der DB-Wert.
        for (AuditIngestionStatus status : AuditIngestionStatus.values()) {
            assertThat(AuditIngestionStatus.valueOf(status.name())).isSameAs(status);
        }
        assertThatThrownBy(() -> AuditIngestionStatus.valueOf("valid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

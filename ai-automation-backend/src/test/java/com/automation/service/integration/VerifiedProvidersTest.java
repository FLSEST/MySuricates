package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedProvidersTest {

    @Test
    void checkedAgainst_returnsTargetForListedProvider() {
        assertThat(VerifiedProviders.checkedAgainst("caldav")).contains("Radicale");
        assertThat(VerifiedProviders.checkedAgainst("github")).isNotBlank();
    }

    @Test
    void checkedAgainst_isNullForUnlistedOrMissingId() {
        assertThat(VerifiedProviders.checkedAgainst("slack")).isNull();
        assertThat(VerifiedProviders.checkedAgainst("unknown")).isNull();
        assertThat(VerifiedProviders.checkedAgainst(null)).isNull();
    }

    @Test
    void everyLedgerEntryHasANonBlankDescription_andAMatchingLookup() {
        assertThat(VerifiedProviders.providerIds()).isNotEmpty();
        for (String id : VerifiedProviders.providerIds()) {
            assertThat(VerifiedProviders.checkedAgainst(id)).as(id).isNotBlank();
            assertThat(id).as("ids are lowercase kebab-case").matches("[a-z][a-z0-9-]*");
        }
    }

    @Test
    void simulatedOnlyProvidersStayOutOfTheLedger() {
        assertThat(VerifiedProviders.providerIds()).doesNotContain("todoist", "slack", "telegram");
    }
}

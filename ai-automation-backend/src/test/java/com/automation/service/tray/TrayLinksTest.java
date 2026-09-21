package com.automation.service.tray;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrayLinksTest {

    @Test
    void clickTarget_approvalsPending_opensApprovalsPage() {
        assertThat(TrayLinks.clickTarget("https://mysuricates.example.com", 2))
                .isEqualTo("https://mysuricates.example.com/approvals");
    }

    @Test
    void clickTarget_nothingPending_opensWebUi() {
        assertThat(TrayLinks.clickTarget("https://mysuricates.example.com/", 0))
                .isEqualTo("https://mysuricates.example.com");
    }

    @Test
    void route_avoidsDoubleSlash() {
        assertThat(TrayLinks.route("https://mysuricates.example.com/", "/activity"))
                .isEqualTo("https://mysuricates.example.com/activity");
    }
}

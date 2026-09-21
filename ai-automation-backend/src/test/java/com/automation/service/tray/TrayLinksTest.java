package com.automation.service.tray;

import com.automation.config.TrayConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrayLinksTest {

    private final TrayConfig config = mock(TrayConfig.class);

    @Test
    void clickTarget_approvalsPending_opensApprovalsPage() {
        when(config.getWebUiUrl()).thenReturn("http://localhost:3000");

        assertThat(TrayLinks.clickTarget(config, 2)).isEqualTo("http://localhost:3000/approvals");
    }

    @Test
    void clickTarget_nothingPending_opensWebUi() {
        when(config.getWebUiUrl()).thenReturn("http://localhost:3000");

        assertThat(TrayLinks.clickTarget(config, 0)).isEqualTo("http://localhost:3000");
    }
}

package com.automation.config;

import com.automation.service.workflow.WorkflowTaggingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard (TODO efc18b68): a test run must not start AI calls in the background.
 *
 * <p>Every created or changed workflow publishes a {@code WorkflowContentChangedEvent}, and
 * {@link WorkflowTaggingService} answers it — and every context start, as a backfill of untagged
 * workflows — with a {@code claude -p} call on the host, run in {@code user.home}. The test JVM runs
 * as the developer, so {@code ExampleProjectIntegrationTest} alone started a call with the
 * developer's CLI login and spent real tokens on every local {@code mvn test} (on CI there is no
 * {@code claude}, the call failed silently). The integration profile switches it off with
 * {@code automation.discovery.auto-tagging-enabled: false} — the same switch an E2E side instance
 * needs (docs/entwicklung.md → E2E tests against a side instance).
 */
@SpringBootTest
@ActiveProfiles("integration")
class IntegrationProfileAiIsolationTest {

    @Autowired
    private WorkflowTaggingService workflowTaggingService;

    @Test
    void workflowTaggingIsSwitchedOffInTheIntegrationProfile() {
        assertThat(workflowTaggingService.isEnabled())
                .as("application-integration.yml must set automation.discovery.auto-tagging-enabled: "
                        + "false — otherwise every workflow a test creates starts a claude call with "
                        + "the developer's login (real tokens)")
                .isFalse();
    }
}

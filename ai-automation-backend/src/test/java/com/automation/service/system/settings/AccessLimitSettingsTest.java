package com.automation.service.system.settings;

import com.automation.config.AssistantGuardrailsConfig;
import com.automation.security.RateLimitConfig;
import com.automation.service.cli.ContainerShellService;
import org.junit.jupiter.api.Test;

import static com.automation.service.system.settings.SettingsGroupsTestSupport.apply;
import static com.automation.service.system.settings.SettingsGroupsTestSupport.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessLimitSettingsTest {

    private final RateLimitConfig rateLimit = new RateLimitConfig();
    private final ContainerShellService shell = mock(ContainerShellService.class);
    private final AssistantGuardrailsConfig guardrails = new AssistantGuardrailsConfig();
    private final AccessLimitSettings settings = new AccessLimitSettings(rateLimit, shell, guardrails);

    @Test
    void appliersSchreibenInRateLimitMountsUndGuardrails() {
        apply(settings, "rate-limit-enabled", "0");
        apply(settings, "rate-limit-requests-per-minute", "17");
        apply(settings, "allow-extra-mounts", "1");
        apply(settings, "assistant-max-actions-per-hour", "4");

        assertThat(rateLimit.isEnabled()).isFalse();
        assertThat(rateLimit.getRequestsPerMinute()).isEqualTo(17);
        verify(shell).setAllowExtraMounts(true);
        assertThat(guardrails.getMaxActionsPerHour()).isEqualTo(4);
    }

    @Test
    void mountSchalterAusBeiJedemWertAusserEins() {
        apply(settings, "allow-extra-mounts", "0");

        verify(shell).setAllowExtraMounts(false);
    }

    @Test
    void ungueltigerWertWirft() {
        assertThatThrownBy(() -> apply(settings, "rate-limit-enabled", "ja"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void currentValuesMappenBooleansAufNullOderEins() {
        rateLimit.setEnabled(true);
        rateLimit.setRequestsPerMinute(55);
        when(shell.isAllowExtraMounts()).thenReturn(false);
        guardrails.setMaxActionsPerHour(9);

        assertThat(values(settings)).containsOnly(
                org.assertj.core.api.Assertions.entry("rateLimitEnabled", 1),
                org.assertj.core.api.Assertions.entry("rateLimitRequestsPerMinute", 55),
                org.assertj.core.api.Assertions.entry("allowExtraMounts", 0),
                org.assertj.core.api.Assertions.entry("assistantMaxActionsPerHour", 9));
    }
}

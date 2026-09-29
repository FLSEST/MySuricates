package com.automation.service.factory;

import com.automation.config.FactoryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider-Wahl der Factory: Anfrage vor Konfiguration, Generator je Provider. */
@ExtendWith(MockitoExtension.class)
class FactoryGeneratorResolverTest {

    @Mock private ClaudeWorkflowGenerator claude;
    @Mock private CodexWorkflowGenerator codex;

    private FactoryGeneratorResolver resolver(String configuredProvider) {
        FactoryConfig config = new FactoryConfig();
        config.setProvider(configuredProvider);
        return new FactoryGeneratorResolver(claude, codex, config);
    }

    @Test
    void requestProvider_winsAndIsUppercased() {
        assertThat(resolver("CLAUDE").resolveProvider("codex")).isEqualTo("CODEX");
    }

    @Test
    void nullOrBlankRequest_fallsBackToTheConfiguredProvider() {
        assertThat(resolver("codex").resolveProvider(null)).isEqualTo("CODEX");
        assertThat(resolver("Claude").resolveProvider("  ")).isEqualTo("CLAUDE");
    }

    @Test
    void generatorFor_picksByProviderCaseInsensitively() {
        assertThat(resolver("CLAUDE").generatorFor("CLAUDE")).isSameAs(claude);
        assertThat(resolver("CLAUDE").generatorFor("codex")).isSameAs(codex);
    }

    @Test
    void generatorFor_rejectsUnknownProviderAndListsTheAvailableOnes() {
        assertThatThrownBy(() -> resolver("CLAUDE").generatorFor("pi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown factory provider: pi")
                .hasMessageContaining("[CLAUDE, CODEX]");
    }
}

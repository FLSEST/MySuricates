package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.automation.util.HostProcessUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeModelCatalogSourceTest {

    /** The host lookup is stubbed on purpose: CI has no {@code claude}, the dev machine has. */
    private static HostProcessUtils host(boolean cliInstalled) {
        HostProcessUtils hostProcessUtils = mock(HostProcessUtils.class);
        when(hostProcessUtils.isOnPath(anyString())).thenReturn(cliInstalled);
        return hostProcessUtils;
    }

    @Test
    void fetch_returnsConfiguredAliasesInOrder() {
        ModelCatalogConfig config = new ModelCatalogConfig();
        ClaudeModelCatalogSource source = new ClaudeModelCatalogSource(config, host(true));

        ModelCatalog catalog = source.fetch();

        assertThat(catalog.providerId()).isEqualTo("CLAUDE");
        assertThat(catalog.available()).isTrue();
        assertThat(catalog.source()).isEqualTo("static-aliases");
        assertThat(catalog.models()).extracting(ModelCatalog.ModelOption::value)
                .containsExactly("fable", "opus", "sonnet", "haiku");
        assertThat(catalog.models().get(1).label()).isEqualTo("opus (latest Opus)");
    }

    @Test
    void fetch_honoursOverriddenAliasList() {
        ModelCatalogConfig config = new ModelCatalogConfig();
        config.setClaudeAliases(List.of("opus"));

        ModelCatalog catalog = new ClaudeModelCatalogSource(config, host(true)).fetch();

        assertThat(catalog.models()).extracting(ModelCatalog.ModelOption::value).containsExactly("opus");
    }

    /**
     * Aliases without an installed CLI would be a catalog of models nobody can call — and this
     * answer is what the dashboard-report assist reads as "no AI connected on this host".
     */
    @Test
    void fetch_reportsUnavailableWhenTheClaudeCliIsNotOnThePath() {
        HostProcessUtils hostProcessUtils = host(false);

        ModelCatalog catalog = new ClaudeModelCatalogSource(new ModelCatalogConfig(), hostProcessUtils).fetch();

        verify(hostProcessUtils).isOnPath("claude");
        assertThat(catalog.available()).isFalse();
        assertThat(catalog.models()).isEmpty();
        assertThat(catalog.reason()).contains("claude").contains("PATH");
        assertThat(catalog.providerId()).isEqualTo("CLAUDE");
    }
}

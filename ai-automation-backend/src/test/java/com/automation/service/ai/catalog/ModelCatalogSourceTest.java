package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.automation.util.HostProcessUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract of {@link ModelCatalogSource} checked against every implementation:
 * {@code fetch()} never throws, reports its own provider id, and an unavailable
 * catalog always carries a reason and no models.
 */
class ModelCatalogSourceTest {

    @TempDir Path emptyCodexHome;

    private List<ModelCatalogSource> brokenHostSources(CliCommandRunner runner) {
        ModelCatalogConfig config = new ModelCatalogConfig();
        config.setCodexHome(emptyCodexHome.toString());
        HostProcessUtils noClaude = mock(HostProcessUtils.class);
        when(noClaude.isOnPath("claude")).thenReturn(false);
        return List.of(
                new ClaudeModelCatalogSource(config, noClaude),
                new PiModelCatalogSource(config, runner),
                new CodexModelCatalogSource(config, runner));
    }

    @Test
    void providerIds_areDistinctRegistryIds() {
        assertThat(brokenHostSources((c, t) -> "")).extracting(ModelCatalogSource::providerId)
                .containsExactly("CLAUDE", "PI", "CODEX");
    }

    @Test
    void fetch_neverThrows_evenWhenTheRunnerFailsUnchecked() {
        for (ModelCatalogSource source : brokenHostSources((c, t) -> { throw new IllegalStateException("boom"); })) {
            assertThatCode(source::fetch).as(source.providerId()).doesNotThrowAnyException();
        }
    }

    @Test
    void failedProbe_isUnavailableWithReasonAndOwnProviderId() {
        for (ModelCatalogSource source : brokenHostSources((c, t) -> { throw new IOException("not installed"); })) {
            ModelCatalog catalog = source.fetch();
            assertThat(catalog.providerId()).isEqualTo(source.providerId());
            assertThat(catalog.available()).as(source.providerId()).isFalse();
            assertThat(catalog.reason()).as(source.providerId()).isNotBlank();
            assertThat(catalog.models()).isEmpty();
        }
    }
}

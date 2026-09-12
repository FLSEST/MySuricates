package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeModelCatalogSourceTest {

    @Test
    void fetch_returnsConfiguredAliasesInOrder() {
        ModelCatalogConfig config = new ModelCatalogConfig();
        ClaudeModelCatalogSource source = new ClaudeModelCatalogSource(config);

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

        ModelCatalog catalog = new ClaudeModelCatalogSource(config).fetch();

        assertThat(catalog.models()).extracting(ModelCatalog.ModelOption::value).containsExactly("opus");
    }
}

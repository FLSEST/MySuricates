package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiModelCatalogSourceTest {

    private static final String TABLE = """
            provider      model                context  max-out  thinking  images
            openai-codex  gpt-5.5              272K     128K     yes       yes
            openai-codex  gpt-5.3-codex-spark  128K     128K     yes       no
            anthropic     claude-opus-5        1M       128K     yes       yes
            ollama        llama3               -        -        no        no
            """;

    private final ModelCatalogConfig config = new ModelCatalogConfig();

    @Test
    void parseTable_realisticOutput_mapsProviderSlashModel() {
        List<ModelCatalog.ModelOption> options = PiModelCatalogSource.parseTable(TABLE);

        assertThat(options).extracting(ModelCatalog.ModelOption::value)
                .containsExactly("openai-codex/gpt-5.5", "openai-codex/gpt-5.3-codex-spark",
                        "anthropic/claude-opus-5", "ollama/llama3");
        assertThat(options.get(0).label()).isEqualTo("openai-codex · gpt-5.5");
    }

    @Test
    void parseTable_contextSuffixes_andThinkingFlag() {
        List<ModelCatalog.ModelOption> options = PiModelCatalogSource.parseTable(TABLE);

        assertThat(options.get(0).contextWindow()).isEqualTo(272_000);
        assertThat(options.get(2).contextWindow()).isEqualTo(1_000_000);
        assertThat(options.get(3).contextWindow()).isNull();
        assertThat(options.get(0).supportsThinking()).isTrue();
        assertThat(options.get(3).supportsThinking()).isFalse();
    }

    @Test
    void parseTable_headerOnly_returnsEmpty() {
        assertThat(PiModelCatalogSource.parseTable("provider  model  context  max-out  thinking  images\n")).isEmpty();
        assertThat(PiModelCatalogSource.parseTable("")).isEmpty();
        assertThat(PiModelCatalogSource.parseTable(null)).isEmpty();
    }

    @Test
    void parseTable_ignoresNoiseBeforeHeader() {
        String withNotice = "pi 0.74.2 — offline mode\n\n" + TABLE;

        assertThat(PiModelCatalogSource.parseTable(withNotice)).hasSize(4);
    }

    @Test
    void parseTokenCount_variants() {
        assertThat(PiModelCatalogSource.parseTokenCount("200K")).isEqualTo(200_000);
        assertThat(PiModelCatalogSource.parseTokenCount("1.5M")).isEqualTo(1_500_000);
        assertThat(PiModelCatalogSource.parseTokenCount("128000")).isEqualTo(128_000);
        assertThat(PiModelCatalogSource.parseTokenCount("-")).isNull();
        assertThat(PiModelCatalogSource.parseTokenCount("n/a")).isNull();
    }

    @Test
    void fetch_runsPiOfflineListModels_andReturnsAvailableCatalog() {
        List<List<String>> seen = new java.util.ArrayList<>();
        PiModelCatalogSource source = new PiModelCatalogSource(config, (cmd, timeout) -> {
            seen.add(cmd);
            return TABLE;
        });

        ModelCatalog catalog = source.fetch();

        assertThat(seen).containsExactly(List.of("pi", "--offline", "--list-models"));
        assertThat(catalog.available()).isTrue();
        assertThat(catalog.providerId()).isEqualTo("PI");
        assertThat(catalog.source()).isEqualTo("pi-cli");
        assertThat(catalog.models()).hasSize(4);
    }

    @Test
    void fetch_cliFailure_returnsUnavailableWithReason_doesNotThrow() {
        PiModelCatalogSource source = new PiModelCatalogSource(config, (cmd, timeout) -> {
            throw new IOException("Cannot run program \"pi\": No such file or directory");
        });

        ModelCatalog catalog = source.fetch();

        assertThat(catalog.available()).isFalse();
        assertThat(catalog.models()).isEmpty();
        assertThat(catalog.reason()).contains("No such file");
    }
}

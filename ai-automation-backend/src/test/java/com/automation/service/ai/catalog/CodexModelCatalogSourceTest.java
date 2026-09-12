package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexModelCatalogSourceTest {

    private static final String BUNDLED_JSON = """
            {"models":[
              {"slug":"gpt-5.5","display_name":"GPT-5.5","description":"Balanced","visibility":"list",
               "supported_in_api":true,"priority":12,"context_window":272000,"supported_reasoning_levels":[]},
              {"slug":"gpt-5.4","display_name":"GPT-5.4","visibility":"hide","priority":16,
               "context_window":272000,"supported_reasoning_levels":[]},
              {"slug":"gpt-6-astra","display_name":"GPT-6-Astra","description":"Newest","visibility":"list",
               "supported_in_api":true,"priority":1,"context_window":272000,
               "supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}]},
              {"slug":"","display_name":"broken","visibility":"list","priority":0}
            ]}
            """;

    private static final String CACHE_JSON = """
            {"fetched_at":"2026-09-12T19:44:12.956233619Z","etag":"W/\\"abc\\"","client_version":"0.153.4",
             "models":[
              {"slug":"gpt-reserve","display_name":"GPT Reserve","visibility":"list","priority":2,
               "context_window":128000,"supported_reasoning_levels":[{"effort":"medium"}]}
            ]}
            """;

    @TempDir
    Path codexHome;

    private ModelCatalogConfig config() {
        ModelCatalogConfig c = new ModelCatalogConfig();
        c.setCodexHome(codexHome.toString());
        return c;
    }

    @Test
    void parseModels_filtersHiddenAndBlankSlugs_sortsByPriority() throws IOException {
        List<ModelCatalog.ModelOption> options =
                CodexModelCatalogSource.parseModels(new ObjectMapper().readTree(BUNDLED_JSON));

        assertThat(options).extracting(ModelCatalog.ModelOption::value)
                .containsExactly("gpt-6-astra", "gpt-5.5");
        assertThat(options.get(0).label()).isEqualTo("GPT-6-Astra");
        assertThat(options.get(0).description()).isEqualTo("Newest");
        assertThat(options.get(0).contextWindow()).isEqualTo(272_000);
        assertThat(options.get(0).supportsThinking()).isTrue();
        assertThat(options.get(1).supportsThinking()).isFalse();
    }

    @Test
    void parseModels_missingOrMalformedRoot_returnsEmpty() throws IOException {
        ObjectMapper om = new ObjectMapper();
        assertThat(CodexModelCatalogSource.parseModels(null)).isEmpty();
        assertThat(CodexModelCatalogSource.parseModels(om.readTree("{}"))).isEmpty();
        assertThat(CodexModelCatalogSource.parseModels(om.readTree("{\"models\":\"nope\"}"))).isEmpty();
    }

    @Test
    void fetch_prefersModelsCacheFile_overCli() throws IOException {
        Files.writeString(codexHome.resolve("models_cache.json"), CACHE_JSON);
        CodexModelCatalogSource source = new CodexModelCatalogSource(config(), (cmd, t) -> {
            throw new AssertionError("CLI must not be called when the cache file is usable");
        });

        ModelCatalog catalog = source.fetch();

        assertThat(catalog.available()).isTrue();
        assertThat(catalog.source()).isEqualTo("codex-cache");
        assertThat(catalog.fetchedAt()).isEqualTo(Instant.parse("2026-09-12T19:44:12.956233619Z"));
        assertThat(catalog.models()).extracting(ModelCatalog.ModelOption::value).containsExactly("gpt-reserve");
    }

    @Test
    void fetch_noCacheFile_fallsBackToBundledCli() {
        List<List<String>> seen = new java.util.ArrayList<>();
        CodexModelCatalogSource source = new CodexModelCatalogSource(config(), (cmd, t) -> {
            seen.add(cmd);
            return BUNDLED_JSON;
        });

        ModelCatalog catalog = source.fetch();

        assertThat(seen).containsExactly(List.of("codex", "debug", "models", "--bundled"));
        assertThat(catalog.available()).isTrue();
        assertThat(catalog.source()).isEqualTo("codex-cli");
        assertThat(catalog.models()).extracting(ModelCatalog.ModelOption::value)
                .containsExactly("gpt-6-astra", "gpt-5.5");
    }

    @Test
    void fetch_corruptCacheFile_isIgnored_andCliUsed() throws IOException {
        Files.writeString(codexHome.resolve("models_cache.json"), "{not json");
        CodexModelCatalogSource source = new CodexModelCatalogSource(config(), (cmd, t) -> BUNDLED_JSON);

        ModelCatalog catalog = source.fetch();

        assertThat(catalog.available()).isTrue();
        assertThat(catalog.source()).isEqualTo("codex-cli");
    }

    @Test
    void fetch_noCacheAndNoCli_returnsUnavailable_doesNotThrow() {
        CodexModelCatalogSource source = new CodexModelCatalogSource(config(), (cmd, t) -> {
            throw new IOException("Cannot run program \"codex\"");
        });

        ModelCatalog catalog = source.fetch();

        assertThat(catalog.available()).isFalse();
        assertThat(catalog.providerId()).isEqualTo("CODEX");
        assertThat(catalog.reason()).contains("codex");
        assertThat(catalog.models()).isEmpty();
    }

    @Test
    void fetch_cliReturnsGarbage_returnsUnavailable() {
        CodexModelCatalogSource source = new CodexModelCatalogSource(config(), (cmd, t) -> "error: boom");

        ModelCatalog catalog = source.fetch();

        assertThat(catalog.available()).isFalse();
        assertThat(catalog.reason()).isNotBlank();
    }
}

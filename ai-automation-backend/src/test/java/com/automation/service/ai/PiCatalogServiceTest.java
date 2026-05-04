package com.automation.service.ai;

import com.automation.config.HostPiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PiCatalogServiceTest {

    private PiCatalogService createService(Path configDir) {
        HostPiConfig config = new HostPiConfig();
        config.setConfigDir(configDir.toString());
        return new PiCatalogService(config);
    }

    @Test
    void notInstalled_whenConfigDirMissing(@TempDir Path tmp) {
        PiCatalogService service = createService(tmp.resolve("does-not-exist"));

        var catalog = service.getCatalog();

        assertThat(catalog.installed()).isFalse();
        assertThat(catalog.authenticatedProviders()).isEmpty();
        assertThat(catalog.customProviders()).isEmpty();
        assertThat(catalog.defaultProvider()).isNull();
    }

    @Test
    void installed_emptyDir_returnsEmptyCatalog(@TempDir Path tmp) {
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        assertThat(catalog.installed()).isTrue();
        assertThat(catalog.authenticatedProviders()).isEmpty();
        assertThat(catalog.customProviders()).isEmpty();
        assertThat(catalog.defaultProvider()).isNull();
    }

    @Test
    void readsAuthenticatedProviders_fromAuthJson(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("auth.json"),
                "{ \"openai-codex\": {\"token\": \"abc\"}, \"anthropic\": {\"apiKey\": \"sk-…\"} }");
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        // Sorted alphabetically for stable UI ordering
        assertThat(catalog.authenticatedProviders()).containsExactly("anthropic", "openai-codex");
    }

    @Test
    void readsDefaultProviderAndModel_fromSettingsJson(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("settings.json"),
                "{\"defaultProvider\": \"openai-codex\", \"defaultModel\": \"gpt-5.5\", \"defaultThinkingLevel\": \"medium\"}");
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        assertThat(catalog.defaultProvider()).isEqualTo("openai-codex");
        assertThat(catalog.defaultModel()).isEqualTo("gpt-5.5");
    }

    @Test
    void readsCustomProviders_fromTopLevelObject(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("models.json"),
                "{\"ollama\": {\"baseUrl\": \"http://localhost:11434\"}, \"lmstudio\": {}}");
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        assertThat(catalog.customProviders()).containsExactly("lmstudio", "ollama");
    }

    @Test
    void readsCustomProviders_fromProvidersWrapper(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("models.json"),
                "{\"providers\": {\"vllm\": {\"baseUrl\": \"http://localhost:8000\"}}}");
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        assertThat(catalog.customProviders()).containsExactly("vllm");
    }

    @Test
    void corruptAuthJson_returnsEmptyAuthList_doesNotThrow(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("auth.json"), "{this is not valid json");
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        assertThat(catalog.installed()).isTrue();
        assertThat(catalog.authenticatedProviders()).isEmpty();
    }

    @Test
    void fullCatalog_combinesAllThreeFiles(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("auth.json"), "{\"openai-codex\": {}}");
        Files.writeString(tmp.resolve("settings.json"),
                "{\"defaultProvider\": \"openai-codex\", \"defaultModel\": \"gpt-5.5\"}");
        Files.writeString(tmp.resolve("models.json"), "{\"ollama\": {}}");
        PiCatalogService service = createService(tmp);

        var catalog = service.getCatalog();

        assertThat(catalog.installed()).isTrue();
        assertThat(catalog.authenticatedProviders()).containsExactly("openai-codex");
        assertThat(catalog.customProviders()).containsExactly("ollama");
        assertThat(catalog.defaultProvider()).isEqualTo("openai-codex");
        assertThat(catalog.defaultModel()).isEqualTo("gpt-5.5");
    }
}

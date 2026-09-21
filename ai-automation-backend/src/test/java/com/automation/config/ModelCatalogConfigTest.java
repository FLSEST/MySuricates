package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static ModelCatalogConfig bind(Map<String, String> properties) {
        String prefix = ModelCatalogConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(ModelCatalogConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_model_catalog() {
        assertThat(ModelCatalogConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(ModelCatalogConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.model-catalog");
    }

    @Test
    void should_have_documented_defaults() {
        ModelCatalogConfig config = new ModelCatalogConfig();

        assertThat(config.getTtlSeconds()).isEqualTo(600);
        assertThat(config.getProbeTimeoutSeconds()).isEqualTo(15);
        assertThat(config.getCodexHome()).isEqualTo(System.getProperty("user.home") + "/.codex");
    }

    @Test
    void should_default_to_undated_claude_aliases_only() {
        // aliases never go stale; a dated model id here would be a bug (CLAUDE.md: never hard-code dated ids)
        assertThat(new ModelCatalogConfig().getClaudeAliases())
                .containsExactly("fable", "opus", "sonnet", "haiku")
                .allSatisfy(alias -> assertThat(alias).doesNotContainPattern("\\d"));
    }

    @Test
    void should_bind_properties_including_alias_list() {
        ModelCatalogConfig config = bind(Map.of(
                "automation.model-catalog.ttl-seconds", "60",
                "automation.model-catalog.probe-timeout-seconds", "3",
                "automation.model-catalog.codex-home", "/opt/codex",
                "automation.model-catalog.claude-aliases[0]", "opus",
                "automation.model-catalog.claude-aliases[1]", "sonnet"));

        assertThat(config.getTtlSeconds()).isEqualTo(60);
        assertThat(config.getProbeTimeoutSeconds()).isEqualTo(3);
        assertThat(config.getCodexHome()).isEqualTo("/opt/codex");
        assertThat(config.getClaudeAliases()).containsExactly("opus", "sonnet");
    }
}

package com.automation.modules;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documented configuration templates (config-examples/, docs/deployment.md) must stay valid
 * and keep their promise as the module catalog grows: minimal = nothing optional, maximal =
 * everything on.
 */
class ConfigExamplesTest {

    private static ModuleRegistry load(String file) throws IOException {
        Path path = Path.of("config-examples", file);
        assertThat(path).exists();
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, new FileSystemResource(path))) {
            environment.getPropertySources().addFirst(source);
        }
        return ModuleRegistry.fromEnvironment(environment);
    }

    @Test
    void minimalTurnsEveryOptionOff() throws IOException {
        ModuleRegistry registry = load("application-minimal.yml");

        assertThat(registry.preset()).isEqualTo(ModulePreset.CORE);
        assertThat(registry.uiEditable()).isFalse();
        assertThat(Arrays.stream(ModuleCatalog.values()).filter(registry::isEnabled)).isEmpty();
    }

    @Test
    void maximalTurnsEverythingOn() throws IOException {
        ModuleRegistry registry = load("application-maximal.yml");

        assertThat(registry.preset()).isEqualTo(ModulePreset.FULL);
        assertThat(registry.uiEditable()).isTrue();
        assertThat(Arrays.stream(ModuleCatalog.values()).filter(m -> !registry.isEnabled(m))).isEmpty();
    }
}

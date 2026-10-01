package com.automation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataHomeTest {

    @TempDir
    Path home;

    @Test
    void freshHome_usesMysuricates() {
        assertThat(DataHome.dir(home)).isEqualTo(home.resolve(".mysuricates"));
    }

    @Test
    void olderInstallation_keepsAutotriggerAsLongAsItExists() throws IOException {
        Files.createDirectories(home.resolve(".autotrigger"));
        Files.createDirectories(home.resolve(".mysuricates"));   // the plugin or run workspaces may create it

        assertThat(DataHome.dir(home)).isEqualTo(home.resolve(".autotrigger"));
    }

    @Test
    void aFileNamedLikeTheOldDirectory_isNoInstallation() throws IOException {
        Files.writeString(home.resolve(".autotrigger"), "x");

        assertThat(DataHome.dir(home)).isEqualTo(home.resolve(".mysuricates"));
    }

    @Test
    void export_passesTheDirectoryOn_butKeepsAValueTheCallerSet() {
        Map<String, String> env = new HashMap<>();
        DataHome.export(env);
        assertThat(env).containsEntry(DataHome.ENV, DataHome.dir().toString());

        Map<String, String> preset = new HashMap<>(Map.of(DataHome.ENV, "/elsewhere"));
        DataHome.export(preset);
        assertThat(preset).containsEntry(DataHome.ENV, "/elsewhere");
    }

    @Test
    void postProcessor_providesTheProperty_withoutOverridingAConfiguredOne() {
        MockEnvironment environment = new MockEnvironment();
        new DataHomeEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("automation.data-home")).isEqualTo(DataHome.dir().toString());

        MockEnvironment configured = new MockEnvironment().withProperty("automation.data-home", "/configured");
        new DataHomeEnvironmentPostProcessor().postProcessEnvironment(configured, new SpringApplication());
        assertThat(configured.getProperty("automation.data-home")).isEqualTo("/configured");
    }

    @Test
    void postProcessor_isLastSoEveryConfiguredValueWins() {
        MockEnvironment environment = new MockEnvironment();
        new DataHomeEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().stream().reduce((a, b) -> b).orElseThrow().getName())
                .isEqualTo("dataHome");
    }
}

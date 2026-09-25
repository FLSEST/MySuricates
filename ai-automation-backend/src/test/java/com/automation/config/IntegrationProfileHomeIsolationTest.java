package com.automation.config;

import com.automation.service.factory.FactorySetupService;
import com.automation.service.memory.SummarizationSetupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard (TODO 6507e230): a test run must not write into the developer's real
 * {@code ~/.autotrigger}.
 *
 * <p>The same trap four times: a startup writer defaults to {@code ${user.home}/.autotrigger/...} and
 * the integration profile does not redirect it — the integration encryption key (2026-08-06), the
 * startup backup (2026-09-13), the seal signing key and, found 2026-09-24, the instruction files that
 * {@link SummarizationSetupService} and {@link FactorySetupService} write on every context start. Those
 * rewrote {@code summarization/claude/CLAUDE.md}, {@code summarization/codex/AGENTS.md} and
 * {@code factory/codex/AGENTS.md} in the real home on every local {@code mvn test}. The profile points
 * all of them at per-JVM temp paths from {@code IntegrationTestDbSessionListener}; the second test
 * catches the next configured default of that kind before it ships.
 */
@SpringBootTest
@ActiveProfiles("integration")
class IntegrationProfileHomeIsolationTest {

    /** The developer's real state directory — the test JVM runs as the developer. */
    private static final Path REAL_STATE_DIR = Path.of(System.getProperty("user.home"), ".autotrigger");

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private SummarizationSetupService summarizationSetup;

    @Autowired
    private FactorySetupService factorySetup;

    @Test
    void setupServicesWriteTheirInstructionsOutsideTheRealHome() {
        List<Path> written = List.of(
                summarizationSetup.getClaudeWorkDir().resolve("CLAUDE.md"),
                summarizationSetup.getCodexWorkDir().resolve("AGENTS.md"),
                factorySetup.getCodexWorkDir().resolve("AGENTS.md"));

        assertThat(written).allSatisfy(file -> {
            assertThat(file.toAbsolutePath().normalize().startsWith(REAL_STATE_DIR))
                    .as("%s is written on every context start — application-integration.yml must point "
                            + "automation.summarization.base-dir and automation.factory.base-dir at "
                            + "integration.setup.dir", file)
                    .isFalse();
            // ...and it is where the context actually wrote, not a path nobody uses
            assertThat(file).exists();
        });
    }

    @Test
    void noConfiguredPathResolvesIntoTheRealStateDirectory() {
        Map<String, String> offenders = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            // Only the application*.yml sources: their defaults are what a context start acts on.
            // System properties and environment variables differ per machine and stay out.
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && source.getName().contains("Config resource")) {
                for (String name : enumerable.getPropertyNames()) {
                    String value = resolved(name);
                    if (value != null && value.contains(REAL_STATE_DIR.toString())) {
                        offenders.put(name, value);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("These keys resolve into the developer's real %s under the integration profile. "
                        + "Redirect each in application-integration.yml to a per-JVM temp path from "
                        + "IntegrationTestDbSessionListener, like automation.backup.directory", REAL_STATE_DIR)
                .isEmpty();
    }

    /** The effective value; null when a placeholder has no value here (such a key is never a path). */
    private String resolved(String name) {
        try {
            return environment.getProperty(name);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }
}

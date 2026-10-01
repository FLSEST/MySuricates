package com.automation.config;

import com.automation.service.factory.FactorySetupService;
import com.automation.service.memory.SummarizationSetupService;
import com.automation.service.workflow.RunWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard (TODO 6507e230): a test run must not write into the developer's real data
 * directory ({@link DataHome}: {@code ~/.mysuricates}, on older installations {@code ~/.autotrigger}).
 *
 * <p>The same trap four times: a startup writer defaults to {@code ${automation.data-home}/...} and
 * the integration profile does not redirect it — the integration encryption key (2026-08-06), the
 * startup backup (2026-09-13), the seal signing key and, found 2026-09-24, the instruction files that
 * {@link SummarizationSetupService} and {@link FactorySetupService} write on every context start. Those
 * rewrote {@code summarization/claude/CLAUDE.md}, {@code summarization/codex/AGENTS.md} and
 * {@code factory/codex/AGENTS.md} in the real home on every local {@code mvn test}. The profile points
 * all of them at per-JVM temp paths from {@code IntegrationTestDbSessionListener}; the second test
 * catches the next default of that kind that an {@code application*.yml} configures. Paths the code
 * builds from {@code user.home} itself are outside its view — so the API key file
 * ({@link InstanceAddress#apiKeyFile()}, overwritten by any test enabling security without a key) and the
 * run workspace root ({@link RunWorkspaceService}, {@code ~/.mysuricates/workspaces} hard-coded) became
 * yml keys as well (TODO 88781724).
 */
@SpringBootTest
@ActiveProfiles("integration")
class IntegrationProfileHomeIsolationTest {

    /** The developer's real state directory — the test JVM runs as the developer. */
    private static final Path REAL_STATE_DIR = DataHome.dir();

    /** Keys whose default in application.yml points into {@link #REAL_STATE_DIR}; the scan must see them. */
    private static final List<String> KNOWN_HOME_KEYS = List.of(
            "automation.summarization.base-dir", "automation.factory.base-dir", "automation.backup.directory",
            "automation.integration.key-file", "automation.seal.key-file",
            "automation.security.api-key-file", "automation.run-workspace.root");

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private SummarizationSetupService summarizationSetup;

    @Autowired
    private FactorySetupService factorySetup;

    @Autowired
    private InstanceAddress instanceAddress;

    @Autowired
    private RunWorkspaceService runWorkspaces;

    /** 88781724: both used to be built from {@code user.home} in code, invisible to the yml scan below. */
    @Test
    void apiKeyFileAndRunWorkspacesLieOutsideTheRealHome() {
        Path keyFile = instanceAddress.apiKeyFile().toAbsolutePath().normalize();
        Path workspaceRoot = Path.of((String) ReflectionTestUtils.getField(runWorkspaces, "root"))
                .toAbsolutePath().normalize();

        assertThat(keyFile.startsWith(REAL_STATE_DIR)).as("API key file %s", keyFile).isFalse();
        assertThat(workspaceRoot.startsWith(REAL_STATE_DIR)).as("run workspace root %s", workspaceRoot).isFalse();
        assertThat(workspaceRoot.startsWith(Path.of(System.getProperty("user.home"), ".mysuricates")))
                .as("run workspace root %s", workspaceRoot).isFalse();
    }

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
        Set<String> scanned = new TreeSet<>();
        Map<String, String> offenders = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            // Only the application*.yml sources: their defaults are what a context start acts on.
            // System properties and environment variables differ per machine and stay out.
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && source.getName().contains("Config resource")) {
                for (String name : enumerable.getPropertyNames()) {
                    scanned.add(name);
                    String value = resolved(name);
                    if (value != null && value.contains(REAL_STATE_DIR.toString())) {
                        offenders.put(name, value);
                    }
                }
            }
        }

        // A renamed property source would otherwise leave the scan empty — and the test green
        assertThat(scanned)
                .as("the scan must reach the application*.yml keys that point into the home by default")
                .containsAll(KNOWN_HOME_KEYS);
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

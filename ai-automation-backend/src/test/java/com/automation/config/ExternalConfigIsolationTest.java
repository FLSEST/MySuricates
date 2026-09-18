package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: a test run must never read an external configuration file.
 *
 * <p>Spring Boot's default {@code spring.config.location} contains
 * {@code optional:file:./config/}, and Surefire forks the test JVM with the module directory
 * {@code ai-automation-backend/} as working directory. Without a counter-measure the untracked
 * developer config {@code ai-automation-backend/config/application.yml} therefore lands in every
 * local test run: it switched {@code automation.modules.ui-editable} and three
 * {@code automation.hardening.*} flags on and broke {@code ModuleGuardIntegrationTest} /
 * {@code ModuleUiToggleIntegrationTest} with seven failures that CI never saw (TODO 39e55535).
 *
 * <p>The fix pins {@code spring.config.location} to the classpath in the Surefire configuration
 * ({@code pom.xml}); {@code application-integration.yml} additionally spells out the keys that
 * leaked. This test pins both halves so the leak cannot come back silently.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ExternalConfigIsolationTest {

    /**
     * Keys the developer config sets, with the value the test environment must see — the catalog
     * defaults ({@code ModuleCatalog}: provenance on, the other hardening flags off) resp. the
     * documented default of the trigger pauses (empty = no pause).
     */
    private static final List<String[]> EXPECTED_DEFAULTS = List.of(
            new String[]{"automation.modules.ui-editable", "false"},
            new String[]{"automation.hardening.provenance", "true"},
            new String[]{"automation.hardening.sealing", "false"},
            new String[]{"automation.hardening.require-sealed-for-unattended", "false"},
            new String[]{"automation.hardening.webhook-signatures", "false"},
            new String[]{"automation.scheduler.skip-until", ""},
            new String[]{"automation.todo-trigger.skip-until", ""});

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void noConfigurationIsLoadedFromAFileOutsideTheClasspath() {
        List<String> fileBased = StreamSupport
                .stream(environment.getPropertySources().spliterator(), false)
                .map(PropertySource::getName)
                // Config-data sources are named "Config resource '<resource>' via location '<loc>'";
                // a filesystem resource renders as "file [config/application.yml]".
                .filter(name -> name.contains("Config resource 'file ["))
                .toList();

        assertThat(fileBased)
                .as("A test run must be reproducible from the classpath alone. These property "
                        + "sources come from files next to the module directory (most likely the "
                        + "untracked dev config ai-automation-backend/config/application.yml) and "
                        + "make local runs measure a different configuration than CI. Fix: keep "
                        + "spring.config.location pinned to the classpath in the surefire "
                        + "configuration of pom.xml.")
                .isEmpty();
    }

    @Test
    void theKeysTheDevConfigOverridesKeepTheirDefaults() {
        for (String[] expected : EXPECTED_DEFAULTS) {
            assertThat(environment.getProperty(expected[0], ""))
                    .as("Property '%s' must keep its default in tests — an external "
                            + "config/application.yml must not reach the test environment", expected[0])
                    .isEqualTo(expected[1]);
        }
    }
}

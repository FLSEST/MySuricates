package com.automation.config;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleKind;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * leaked. All three tests below are needed to pin it: the first one is the only one that fails on
 * a machine (or CI) that has no {@code config/} directory at all, i.e. it is what keeps the
 * Surefire entry from being dropped unnoticed.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ExternalConfigIsolationTest {

    @Autowired
    private ConfigurableEnvironment environment;

    /**
     * The deterministic half of the guard: independent of whether this machine happens to have a
     * {@code config/application.yml}, the Surefire pin itself must be in place.
     */
    @Test
    void surefirePinsSpringConfigLocationToTheClasspath() {
        String location = environment.getProperty("spring.config.location");

        assertThat(location)
                .as("Surefire must pin spring.config.location to the classpath so that no external "
                        + "config/application.yml can join a test run — see the "
                        + "systemPropertyVariables block of maven-surefire-plugin in pom.xml "
                        + "(TODO 39e55535). When running from an IDE, set the same system property "
                        + "in the run configuration: "
                        + "-Dspring.config.location=optional:classpath:/,optional:classpath:/config/")
                .isNotNull()
                .doesNotContain("file:");
    }

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
                        + "configuration of pom.xml; for IDE runs set the same property in the "
                        + "run configuration.")
                .isEmpty();
    }

    @Test
    void theKeysTheDevConfigOverridesKeepTheirDefaults() {
        expectedDefaults().forEach((key, expected) ->
                assertThat(environment.getProperty(key, ""))
                        .as("Property '%s' must keep its default in tests — an external "
                                + "config/application.yml must not reach the test environment", key)
                        .isEqualTo(expected));
    }

    /**
     * The keys the developer config sets, mapped to the value a clean checkout has. Derived from
     * the code that owns each default ({@link ModuleCatalog}, {@link SchedulerConfig},
     * {@link TodoTriggerConfig}) instead of repeating literals, so this test and
     * {@code application-integration.yml} cannot drift away from the catalog together.
     */
    private static Map<String, String> expectedDefaults() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(ModuleRegistry.UI_EDITABLE_PROPERTY, "false");
        for (ModuleCatalog module : ModuleCatalog.values()) {
            if (module.kind() == ModuleKind.HARDENING) {
                expected.put(ModuleKind.HARDENING.propertyPrefix() + "." + module.key(),
                        String.valueOf(module.defaultEnabled()));
            }
        }
        SchedulerConfig schedulerDefaults = new SchedulerConfig();
        expected.put("automation.scheduler.skip-until", schedulerDefaults.getSkipUntil());
        expected.put("automation.scheduler.catch-up-on-resume",
                String.valueOf(schedulerDefaults.isCatchUpOnResume()));
        expected.put("automation.todo-trigger.skip-until", new TodoTriggerConfig().getSkipUntil());
        return expected;
    }
}

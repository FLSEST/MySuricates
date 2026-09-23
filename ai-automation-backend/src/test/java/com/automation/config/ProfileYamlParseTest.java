package com.automation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Loads every shipped {@code application*.yml} the way Spring does at startup. That loader rejects a
 * key that appears twice in one mapping; plain SnakeYAML ({@code new Yaml()}, as in
 * {@link DataSourceConfigGuardTest}) silently keeps the last one. The stable and test profiles are
 * active in no test, so a second top-level {@code automation:} block in both files only surfaced
 * when the rebuilt test container refused to start.
 *
 * <p>Reads the copies on the classpath, not {@code src/main/resources}: Maven filters
 * {@code @project.version@} in {@code application.yml}, and only the filtered file is valid YAML.
 */
class ProfileYamlParseTest {

    static List<Path> profileFiles() throws IOException, URISyntaxException {
        URL base = ProfileYamlParseTest.class.getResource("/application.yml");
        assertThat(base).as("application.yml on the test classpath").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(base.toURI()).getParent())) {
            return files.filter(p -> p.getFileName().toString().matches("application(-[^.]+)?\\.ya?ml"))
                    .sorted()
                    .toList();
        }
    }

    /** Guards the glob: finding nothing would leave the parameterized test green without a run. */
    @Test
    void findsTheProfilesNoTestActivates() throws Exception {
        assertThat(profileFiles()).map(p -> p.getFileName().toString())
                .contains("application.yml", "application-stable.yml", "application-test.yml", "application-prod.yml");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileFiles")
    void loadsLikeAtStartup(Path file) {
        assertThatCode(() -> new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file)))
                .as(file.toString())
                .doesNotThrowAnyException();
    }

    /** The header exposes internal timings and SQL counts; only the dev instance keeps it on. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-stable.yml", "application-test.yml", "application-prod.yml"})
    void outwardFacingProfilesSwitchServerTimingOff(String name) throws Exception {
        Path file = profileFiles().stream()
                .filter(p -> p.getFileName().toString().equals(name))
                .findFirst().orElseThrow();

        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(name, new FileSystemResource(file));

        assertThat(sources).map(s -> String.valueOf(s.getProperty("automation.request-metrics.server-timing-enabled")))
                .as(name)
                .containsExactly("false");
    }
}

package com.automation.architecture;

import com.automation.service.containerimage.AgentImageCatalog;
import com.automation.service.containerimage.AgentImageCatalog.AgentImage;
import com.automation.service.containerimage.AgentImageCatalog.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps container-images/agent-images.json equal to the agent Dockerfiles (TODO 244e5de5). The container
 * overview reports an image as outdated when its label differs from the catalog's sha256 — a catalog
 * that lags behind its Dockerfile would call every freshly built image outdated, and a catalog that
 * names a tool the image no longer has would promise something the workflow cannot use.
 */
class AgentImageCatalogGateTest {

    private static final Pattern COMPOSE_SERVICE = Pattern.compile("(?m)^  (agent-[a-z]+):\\s*$");
    private static final Pattern COMPOSE_IMAGE = Pattern.compile("(?m)^    image: (automation-agent-[a-z]+:[\\w.-]+)\\s*$");

    private final List<AgentImage> catalog = new AgentImageCatalog(new ObjectMapper()).images();

    @Test
    void everyAgentServiceOfTheComposeFileIsInTheCatalogAndNothingElse() throws IOException {
        String compose = Files.readString(backendDir().resolve("docker-compose.yml"));
        Set<String> services = matches(COMPOSE_SERVICE, compose);
        Set<String> images = matches(COMPOSE_IMAGE, compose);
        assertThat(sorted(catalog.stream().map(AgentImage::service)))
                .as("catalog services vs. agent services in docker-compose.yml").isEqualTo(services);
        assertThat(sorted(catalog.stream().map(AgentImage::image)))
                .as("catalog images vs. image: lines in docker-compose.yml").isEqualTo(images);
        for (AgentImage image : catalog) {
            assertThat(image.dockerfile()).isEqualTo("docker/Dockerfile." + image.service());
            assertThat(compose).as("compose passes the Dockerfile identity of " + image.service())
                    .contains("DOCKERFILE_SHA256: ${AGENT_" + image.service().substring("agent-".length()).toUpperCase()
                            + "_DOCKERFILE_SHA256:-}");
        }
    }

    @Test
    void everyAgentDockerfileIsInTheCatalog() throws IOException {
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(backendDir().resolve("docker"))) {
            onDisk = sorted(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("Dockerfile.agent-"))
                    .map(n -> "docker/" + n));
        }
        assertThat(sorted(catalog.stream().map(AgentImage::dockerfile)))
                .isEqualTo(onDisk);
    }

    @Test
    void catalogChecksumMatchesTheDockerfile() throws IOException {
        for (AgentImage image : catalog) {
            String actual = sha256(Files.readAllBytes(backendDir().resolve(image.dockerfile())));
            assertThat(image.dockerfileSha256())
                    .as("%s changed — put its new sha256 into %s: %s", image.dockerfile(), AgentImageCatalog.RESOURCE, actual)
                    .isEqualTo(actual);
        }
    }

    @Test
    void everyDockerfileWritesItsIdentityLabel() throws IOException {
        for (AgentImage image : catalog) {
            String dockerfile = Files.readString(backendDir().resolve(image.dockerfile()));
            assertThat(dockerfile).as(image.dockerfile())
                    .contains("ARG DOCKERFILE_SHA256=")
                    .contains("LABEL " + AgentImageCatalog.DOCKERFILE_LABEL + "=$DOCKERFILE_SHA256");
        }
    }

    @Test
    void baseImageAndToolsMatchTheDockerfile() throws IOException {
        for (AgentImage image : catalog) {
            String dockerfile = Files.readString(backendDir().resolve(image.dockerfile()));
            assertThat(dockerfile).as("base of " + image.image()).containsPattern("(?m)^FROM " + Pattern.quote(image.base()) + "\\s*$");
            assertThat(image.purpose()).as("purpose of " + image.image()).containsKeys("de", "en");
            for (Tool tool : image.tools()) {
                String what = image.image() + " → " + tool.name();
                switch (tool.source()) {
                    case "release" -> assertThat(dockerfile).as(what)
                            .containsPattern("(?m)^ARG " + Pattern.quote(tool.arg()) + "=" + Pattern.quote(tool.version()) + "\\s*$");
                    case "base" -> assertThat(image.base()).as(what).contains(tool.version());
                    case "apt", "pip" -> {
                        assertThat(tool.version()).as(what + " is unpinned, so no version").isNull();
                        assertThat(dockerfile).as(what).containsPattern("(?<![\\w-])" + Pattern.quote(tool.name()) + "(?![\\w-])");
                    }
                    default -> throw new AssertionError(what + ": unknown source " + tool.source());
                }
            }
        }
    }

    private static Set<String> sorted(Stream<String> values) {
        return values.collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> result = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Maven runs with user.dir = module dir; fall back to the repo-root layout for IDE runners. */
    private static Path backendDir() {
        Path module = Paths.get("docker");
        if (Files.isDirectory(module)) return Paths.get("");
        Path fromRepoRoot = Paths.get("ai-automation-backend");
        assertThat(fromRepoRoot.resolve("docker")).as("docker/ not found from " + Paths.get("").toAbsolutePath()).isDirectory();
        return fromRepoRoot;
    }
}

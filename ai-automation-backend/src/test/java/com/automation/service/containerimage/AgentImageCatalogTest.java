package com.automation.service.containerimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentImageCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentImageCatalog catalog = new AgentImageCatalog(JSON);

    @Test
    void loadsTheShippedCatalog_withAPinnedDockerfileHashPerImage() {
        assertThat(catalog.images()).isNotEmpty();
        assertThat(catalog.images()).allSatisfy(image -> {
            assertThat(image.image()).endsWith(":latest");
            assertThat(image.dockerfile()).startsWith("docker/Dockerfile.");
            assertThat(image.dockerfileSha256()).matches("[0-9a-f]{64}");
            assertThat(image.purpose()).containsKeys("de", "en");
        });
    }

    @Test
    void find_acceptsAReferenceWithoutTag_asLatest() {
        String first = catalog.images().get(0).image();
        String withoutTag = first.substring(0, first.indexOf(':'));

        assertThat(catalog.find(withoutTag)).contains(catalog.images().get(0));
        assertThat(catalog.find("  " + first + " ")).contains(catalog.images().get(0));
    }

    @Test
    void find_isEmpty_forUnknownOtherTagsAndNull() {
        String first = catalog.images().get(0).image();
        String otherTag = first.substring(0, first.indexOf(':')) + ":1.0";

        assertThat(catalog.find("not-an-agent-image")).isEmpty();
        assertThat(catalog.find(otherTag)).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "automation-agent-node, automation-agent-node:latest",
            "' automation-agent-node ', automation-agent-node:latest",
            "automation-agent-node:2.0, automation-agent-node:2.0",
            "ghcr.io/org/agent, ghcr.io/org/agent:latest",
            "localhost:5000/agent, localhost:5000/agent:latest",
            "localhost:5000/agent:1, localhost:5000/agent:1",
            "agent@sha256:abc, agent@sha256:abc",
            "'{{ image }}', '{{ image }}'",
            "'registry/{{ name }}', 'registry/{{ name }}'"})
    void normalize_addsLatestOnlyWhenNoTagOrDigestIsGiven(String reference, String expected) {
        assertThat(AgentImageCatalog.normalize(reference)).isEqualTo(expected);
    }

    @Test
    void normalize_keepsNull() {
        assertThat(AgentImageCatalog.normalize(null)).isNull();
    }

    @Test
    void parse_readsAllFields_withNullForMissingOrNullVersionAndArg() throws IOException {
        JsonNode root = JSON.readTree("""
                {"images": [{
                  "image": "a:latest", "service": "agent-a", "dockerfile": "docker/Dockerfile.a",
                  "dockerfileSha256": "abc", "base": "ubuntu:24.04",
                  "purpose": {"de": "Zweck", "en": "Purpose"},
                  "tools": [
                    {"name": "trivy", "version": "0.1.0", "source": "release", "arg": "TRIVY_VERSION"},
                    {"name": "gh", "source": "apt"},
                    {"name": "jq", "version": null, "source": "apt", "arg": null}
                  ]}]}
                """);

        List<AgentImageCatalog.AgentImage> images = AgentImageCatalog.parse(root);

        assertThat(images).containsExactly(new AgentImageCatalog.AgentImage("a:latest", "agent-a",
                "docker/Dockerfile.a", "abc", "ubuntu:24.04", Map.of("de", "Zweck", "en", "Purpose"), List.of(
                new AgentImageCatalog.Tool("trivy", "0.1.0", "release", "TRIVY_VERSION"),
                new AgentImageCatalog.Tool("gh", null, "apt", null),
                new AgentImageCatalog.Tool("jq", null, "apt", null))));
    }

    @Test
    void parse_givesAnEmptyImmutableList_withoutImages() throws IOException {
        List<AgentImageCatalog.AgentImage> images = AgentImageCatalog.parse(JSON.readTree("{}"));

        assertThat(images).isEmpty();
        assertThatThrownBy(() -> images.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parse_toleratesAnImageWithoutPurposeAndTools() throws IOException {
        List<AgentImageCatalog.AgentImage> images = AgentImageCatalog.parse(JSON.readTree("{\"images\": [{\"image\": \"x\"}]}"));

        assertThat(images).hasSize(1);
        assertThat(images.get(0).purpose()).isEmpty();
        assertThat(images.get(0).tools()).isEmpty();
        assertThat(images.get(0).service()).isEmpty();
    }

    @Test
    void load_failsUnchecked_when_theResourceCannotBeParsed() throws IOException {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.readTree(any(InputStream.class))).thenThrow(new IOException("bad json"));

        assertThatThrownBy(() -> AgentImageCatalog.load(broken))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Agent image catalog " + AgentImageCatalog.RESOURCE + " unreadable");
    }
}

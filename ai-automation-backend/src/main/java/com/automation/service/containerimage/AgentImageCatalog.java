package com.automation.service.containerimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agent images this platform version ships, read from {@code container-images/agent-images.json}
 * (TODO 244e5de5). Each entry carries the sha256 of its Dockerfile; an image whose label
 * {@value #DOCKERFILE_LABEL} differs was built from another Dockerfile and counts as outdated.
 * AgentImageCatalogGateTest keeps the file equal to the Dockerfiles under {@code docker/}.
 */
@Component
public class AgentImageCatalog {

    public static final String RESOURCE = "container-images/agent-images.json";
    public static final String DOCKERFILE_LABEL = "org.mysuricates.dockerfile-sha256";

    /**
     * A tool inside an image; {@code version} is null for unpinned packages (apt, pip). {@code arg} is the
     * pinning ARG for a release and the pin file (package.json, requirements.txt) for npm and pip-hash.
     */
    public record Tool(String name, String version, String source, String arg) {}

    public record AgentImage(String image, String service, String dockerfile, String dockerfileSha256,
                             String base, Map<String, String> purpose, List<Tool> tools) {}

    private final List<AgentImage> images;

    public AgentImageCatalog(ObjectMapper objectMapper) {
        this.images = load(objectMapper);
    }

    public List<AgentImage> images() {
        return images;
    }

    /** The catalog entry for an image reference; a reference without tag means {@code :latest}. */
    public Optional<AgentImage> find(String imageReference) {
        String normalized = normalize(imageReference);
        return images.stream().filter(image -> image.image().equals(normalized)).findFirst();
    }

    /**
     * {@code automation-agent-node} → {@code automation-agent-node:latest}; tags, digests and placeholders
     * ({@code {{ image }}}, resolved only at run time) stay as written.
     */
    public static String normalize(String imageReference) {
        if (imageReference == null) return null;
        String trimmed = imageReference.trim();
        if (trimmed.contains("{{")) return trimmed;
        int slash = trimmed.lastIndexOf('/');
        String lastSegment = trimmed.substring(slash + 1);
        if (lastSegment.contains(":") || lastSegment.contains("@")) return trimmed;
        return trimmed + ":latest";
    }

    static List<AgentImage> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return parse(objectMapper.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Agent image catalog " + RESOURCE + " unreadable", e);
        }
    }

    static List<AgentImage> parse(JsonNode root) {
        List<AgentImage> result = new ArrayList<>();
        for (JsonNode node : root.path("images")) {
            Map<String, String> purpose = new LinkedHashMap<>();
            node.path("purpose").properties().forEach(e -> purpose.put(e.getKey(), e.getValue().asText()));
            List<Tool> tools = new ArrayList<>();
            for (JsonNode tool : node.path("tools")) {
                tools.add(new Tool(tool.path("name").asText(), textOrNull(tool, "version"),
                        tool.path("source").asText(), textOrNull(tool, "arg")));
            }
            result.add(new AgentImage(node.path("image").asText(), node.path("service").asText(),
                    node.path("dockerfile").asText(), node.path("dockerfileSha256").asText(),
                    node.path("base").asText(), Map.copyOf(purpose), List.copyOf(tools)));
        }
        return List.copyOf(result);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

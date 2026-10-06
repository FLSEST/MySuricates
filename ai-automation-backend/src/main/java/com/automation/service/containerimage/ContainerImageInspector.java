package com.automation.service.containerimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Reads build date and labels of a local image through the Docker API (TODO 244e5de5).
 * Slow (a Docker round trip per image), so never call it inside a transaction.
 */
@Component
public class ContainerImageInspector {

    public record ImageFacts(String created, Map<String, String> labels) {}

    private final DockerClient dockerClient;

    public ContainerImageInspector(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /** Empty when the image does not exist locally; any other Docker error propagates. */
    public Optional<ImageFacts> inspect(String image) {
        try {
            InspectImageResponse response = dockerClient.inspectImageCmd(image).exec();
            Map<String, String> labels = response.getConfig() == null || response.getConfig().getLabels() == null
                    ? Map.of() : response.getConfig().getLabels();
            return Optional.of(new ImageFacts(response.getCreated(), labels));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}

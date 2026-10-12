package com.automation.service.containerimage;

import com.automation.entity.ProjectType;
import com.automation.service.cli.ContainerImageAllowlist;
import com.automation.service.containerimage.AgentImageCatalog.AgentImage;
import com.automation.service.containerimage.ContainerImageInspector.ImageFacts;
import com.automation.service.containerimage.ContainerImageUsage.WorkflowUse;
import com.automation.service.project.DockerContainerGateway;
import com.automation.service.project.DockerAccess;
import com.automation.service.project.DockerExpectation;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The container overview behind the menu entry Container (TODO 244e5de5): every shipped agent image
 * plus every other image a workflow names, with tools, build state, allowlist verdict and who uses it.
 *
 * <p>Read-only on purpose: building an image downloads from the network and runs on the host, so it
 * stays a command for a person ({@code scripts/build-agent-images.sh}), never an API call. Not
 * transactional: the workflow list is one short read, the Docker calls come after it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContainerImageOverviewService {

    public enum Status {
        /** Label matches the catalog: built from the Dockerfile this version ships. */
        CURRENT,
        /** Label differs: built from another Dockerfile, rebuild. */
        OUTDATED,
        /** Built without scripts/build-agent-images.sh, so its Dockerfile is unknown. */
        UNLABELED,
        /** Not present locally. */
        MISSING,
        /** Present; a foreign image has no catalog entry to compare with. */
        PRESENT,
        /** Not checkable: Docker unreachable, the reference is a placeholder, or the inspection failed. */
        UNKNOWN
    }

    public record ImageView(String image, boolean shipped, Map<String, String> purpose, String base,
                            List<AgentImageCatalog.Tool> tools, Status status, String builtAt, boolean allowed,
                            String buildCommand, List<WorkflowUse> workflows, List<String> projectTypes) {}

    /**
     * {@code docker}: CONNECTED, UNAVAILABLE or NOT_CONFIGURED, as in the system health. {@code inContainer},
     * {@code socketSwitch} and {@code switchSource} let the page say why Docker is missing in an instance that
     * itself runs in a container and which switch mounts the socket (TODO 0ad8853b); both strings may be null.
     */
    public record Overview(String docker, boolean allowlistActive, List<ImageView> images, boolean inContainer,
                           String socketSwitch, String switchSource) {}

    static final String BUILD_SCRIPT = "scripts/build-agent-images.sh";

    private final AgentImageCatalog catalog;
    private final ContainerImageInspector inspector;
    private final DockerAccess dockerAccess;
    private final ContainerImageAllowlist allowlist;
    private final WorkflowService workflowService;

    public Overview overview() {
        Map<String, List<WorkflowUse>> uses = ContainerImageUsage.scan(workflowService.findAll());
        DockerAccess.Status access = dockerAccess.status();
        boolean dockerUp = access.reachable();
        String docker = dockerState(access.state());

        List<ImageView> images = new ArrayList<>();
        for (AgentImage image : catalog.images()) {
            State state = state(image.image(), dockerUp, facts -> labelStatus(facts, image));
            images.add(new ImageView(image.image(), true, image.purpose(), image.base(), image.tools(),
                    state.status(), state.builtAt(), allowlist.isAllowed(image.image()),
                    BUILD_SCRIPT + " " + image.service(), uses.getOrDefault(image.image(), List.of()),
                    projectTypes(image.image())));
        }
        uses.forEach((reference, workflows) -> {
            if (catalog.find(reference).isPresent()) return;
            State state = state(reference, dockerUp, facts -> Status.PRESENT);
            images.add(new ImageView(reference, false, Map.of(), null, List.of(), state.status(), state.builtAt(),
                    allowlist.isAllowed(reference), null, workflows, List.of()));
        });
        return new Overview(docker, !allowlist.getEntries().isEmpty(), images, access.inContainer(),
                access.socketSwitch(), access.switchSource());
    }

    private record State(Status status, String builtAt) {}

    /** One Docker round trip per image; {@code present} decides the status of an image that exists. */
    private State state(String reference, boolean dockerUp, Function<ImageFacts, Status> present) {
        if (!dockerUp || isPlaceholder(reference)) return new State(Status.UNKNOWN, null);
        try {
            return inspector.inspect(reference)
                    .map(facts -> new State(present.apply(facts), facts.created()))
                    .orElse(new State(Status.MISSING, null));
        } catch (RuntimeException e) {
            log.debug("Container overview: inspecting {} failed: {}", reference, e.getMessage());
            return new State(Status.UNKNOWN, null);
        }
    }

    private static Status labelStatus(ImageFacts facts, AgentImage image) {
        String label = facts.labels().get(AgentImageCatalog.DOCKERFILE_LABEL);
        if (label == null || label.isBlank()) return Status.UNLABELED;
        return label.equals(image.dockerfileSha256()) ? Status.CURRENT : Status.OUTDATED;
    }

    private static String dockerState(DockerExpectation.State state) {
        return switch (state) {
            case UP -> "CONNECTED";
            case DOWN -> "UNAVAILABLE";
            case NOT_CONFIGURED -> "NOT_CONFIGURED";
        };
    }

    private static boolean isPlaceholder(String reference) {
        return reference.contains("{{");
    }

    private static List<String> projectTypes(String image) {
        return Arrays.stream(ProjectType.values())
                .filter(type -> DockerContainerGateway.imageFor(type).equals(image))
                .map(Enum::name)
                .toList();
    }
}

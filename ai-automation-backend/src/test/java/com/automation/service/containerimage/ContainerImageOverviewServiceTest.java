package com.automation.service.containerimage;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.cli.ContainerImageAllowlist;
import com.automation.service.containerimage.ContainerImageInspector.ImageFacts;
import com.automation.service.containerimage.ContainerImageOverviewService.ImageView;
import com.automation.service.containerimage.ContainerImageOverviewService.Overview;
import com.automation.service.containerimage.ContainerImageOverviewService.Status;
import com.automation.service.project.DockerAccess;
import com.automation.service.project.DockerExpectation;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerImageOverviewServiceTest {

    private static final String DEVOPS = "automation-agent-devops:latest";
    private static final String NODE = "automation-agent-node:latest";

    private final AgentImageCatalog catalog = new AgentImageCatalog(new ObjectMapper());
    private final ContainerImageInspector inspector = mock(ContainerImageInspector.class);
    private final DockerAccess docker = mock(DockerAccess.class);
    private final ContainerImageAllowlist allowlist = mock(ContainerImageAllowlist.class);
    private final WorkflowService workflows = mock(WorkflowService.class);
    private final ContainerImageOverviewService service =
            new ContainerImageOverviewService(catalog, inspector, docker, allowlist, workflows);

    private static DockerAccess.Status onHost(DockerExpectation.State state) {
        return new DockerAccess.Status(state, false, null, null);
    }

    @BeforeEach
    void setUp() {
        when(docker.status()).thenReturn(onHost(DockerExpectation.State.UP));
        when(allowlist.isAllowed(anyString())).thenReturn(true);
        when(allowlist.getEntries()).thenReturn(List.of());
        when(workflows.findAll()).thenReturn(List.of());
        when(inspector.inspect(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void everyShippedImageIsListedWithToolsAndBuildCommand() {
        Overview overview = service.overview();

        assertThat(overview.docker()).isEqualTo("CONNECTED");
        assertThat(overview.allowlistActive()).isFalse();
        assertThat(overview.images()).extracting(ImageView::image).containsExactlyElementsOf(
                catalog.images().stream().map(AgentImageCatalog.AgentImage::image).toList());
        ImageView devops = image(overview, DEVOPS);
        assertThat(devops.shipped()).isTrue();
        assertThat(devops.tools()).extracting(AgentImageCatalog.Tool::name).contains("trivy", "gitleaks");
        assertThat(devops.buildCommand()).isEqualTo("scripts/build-agent-images.sh agent-devops");
        assertThat(devops.status()).isEqualTo(Status.MISSING);
    }

    @Test
    void labelDecidesBetweenCurrentOutdatedAndUnlabeled() {
        String expected = catalog.find(DEVOPS).orElseThrow().dockerfileSha256();
        when(inspector.inspect(DEVOPS)).thenReturn(Optional.of(new ImageFacts("2026-10-06T07:17:18Z",
                Map.of(AgentImageCatalog.DOCKERFILE_LABEL, expected))));
        when(inspector.inspect(NODE)).thenReturn(Optional.of(new ImageFacts("2026-02-16T10:00:00Z",
                Map.of(AgentImageCatalog.DOCKERFILE_LABEL, "0".repeat(64)))));
        when(inspector.inspect("automation-agent-python:latest"))
                .thenReturn(Optional.of(new ImageFacts("2026-02-16T10:00:00Z", Map.of())));

        Overview overview = service.overview();

        assertThat(image(overview, DEVOPS).status()).isEqualTo(Status.CURRENT);
        assertThat(image(overview, DEVOPS).builtAt()).isEqualTo("2026-10-06T07:17:18Z");
        assertThat(image(overview, NODE).status()).isEqualTo(Status.OUTDATED);
        assertThat(image(overview, "automation-agent-python:latest").status()).isEqualTo(Status.UNLABELED);
    }

    @Test
    void withoutDockerNothingIsInspectedAndEverythingIsUnknown() {
        when(docker.status()).thenReturn(onHost(DockerExpectation.State.NOT_CONFIGURED));

        Overview overview = service.overview();

        assertThat(overview.docker()).isEqualTo("NOT_CONFIGURED");
        assertThat(overview.images()).extracting(ImageView::status).containsOnly(Status.UNKNOWN);
        verify(inspector, never()).inspect(anyString());
    }

    @Test
    void dockerReachedOnlyThroughTheCliIsNotConnectedAndNotInspected() {
        when(docker.status()).thenReturn(new DockerAccess.Status(DockerExpectation.State.UP, false, null, null,
                false, true));

        Overview overview = service.overview();

        assertThat(overview.docker()).isEqualTo("CLI_ONLY");
        assertThat(overview.images()).extracting(ImageView::status).containsOnly(Status.UNKNOWN);
        assertThat(overview.images()).extracting(ImageView::builtAt).containsOnlyNulls();
        verify(inspector, never()).inspect(anyString());
    }

    @Test
    void anInstanceInAContainerNamesItsSocketSwitch() {
        when(docker.status()).thenReturn(new DockerAccess.Status(DockerExpectation.State.NOT_CONFIGURED, true,
                "TEST_DOCKER_SOCKET", "/home/user/.config/mysuricates/staging.env"));

        Overview overview = service.overview();

        assertThat(overview.docker()).isEqualTo("NOT_CONFIGURED");
        assertThat(overview.inContainer()).isTrue();
        assertThat(overview.socketSwitch()).isEqualTo("TEST_DOCKER_SOCKET");
        assertThat(overview.switchSource()).isEqualTo("/home/user/.config/mysuricates/staging.env");
        assertThat(overview.tryout()).isFalse();
    }

    @Test
    void theTryoutIsReportedWithoutASwitch() {
        when(docker.status()).thenReturn(new DockerAccess.Status(DockerExpectation.State.NOT_CONFIGURED, true,
                null, null, true));

        Overview overview = service.overview();

        assertThat(overview.inContainer()).isTrue();
        assertThat(overview.tryout()).isTrue();
        assertThat(overview.socketSwitch()).isNull();
    }

    @Test
    void configuredButSilentDockerIsUnavailable() {
        when(docker.status()).thenReturn(onHost(DockerExpectation.State.DOWN));

        assertThat(service.overview().docker()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void failingInspectionIsUnknownNotMissing() {
        when(inspector.inspect(DEVOPS)).thenThrow(new RuntimeException("socket closed"));

        assertThat(image(service.overview(), DEVOPS).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void workflowUsesAttachToShippedAndForeignImages() {
        when(workflows.findAll()).thenReturn(List.of(
                workflow("w1", "Security", block("trivy", Map.of("container_image", "automation-agent-devops",
                        "container_args", List.of("trivy", "fs", "/workspace")))),
                workflow("w2", "Lint", block("lint", Map.of("container_image", "alpine:3", "command", "true"))),
                workflow("w3", "Dynamic", block("dyn", Map.of("container_image", "{{ image }}", "command", "true")))));
        when(inspector.inspect("alpine:3")).thenReturn(Optional.of(new ImageFacts("2026-01-01T00:00:00Z", Map.of())));
        when(allowlist.isAllowed("alpine:3")).thenReturn(false);

        Overview overview = service.overview();

        assertThat(image(overview, DEVOPS).workflows()).singleElement()
                .satisfies(use -> assertThat(use.mode()).isEqualTo(ContainerImageUsage.Mode.TOOL));
        ImageView alpine = image(overview, "alpine:3");
        assertThat(alpine.shipped()).isFalse();
        assertThat(alpine.status()).isEqualTo(Status.PRESENT);
        assertThat(alpine.allowed()).isFalse();
        assertThat(alpine.buildCommand()).isNull();
        assertThat(alpine.tools()).isEmpty();
        assertThat(image(overview, "{{ image }}").status()).isEqualTo(Status.UNKNOWN);
        verify(inspector, never()).inspect("{{ image }}");
    }

    @Test
    void projectContainersNameTheirProjectTypes() {
        Overview overview = service.overview();

        assertThat(image(overview, NODE).projectTypes()).containsExactlyInAnyOrder("CODE", "PERSONAL");
        assertThat(image(overview, "automation-agent-python:latest").projectTypes())
                .containsExactlyInAnyOrder("RESEARCH", "CONTENT", "BUSINESS");
        assertThat(image(overview, DEVOPS).projectTypes()).isEmpty();
    }

    @Test
    void activeAllowlistIsReported() {
        when(allowlist.getEntries()).thenReturn(List.of(DEVOPS));

        assertThat(service.overview().allowlistActive()).isTrue();
    }

    private static ImageView image(Overview overview, String image) {
        return overview.images().stream().filter(view -> view.image().equals(image)).findFirst().orElseThrow();
    }

    private static Workflow workflow(String id, String name, BlockDefinition block) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setBlocks(new ArrayList<>(List.of(block)));
        return workflow;
    }

    private static BlockDefinition block(String name, Map<String, Object> config) {
        BlockDefinition block = new BlockDefinition();
        block.setType("shell");
        block.setName(name);
        block.setConfig(new HashMap<>(config));
        return block;
    }
}

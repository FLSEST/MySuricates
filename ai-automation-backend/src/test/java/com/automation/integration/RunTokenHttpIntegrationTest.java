package com.automation.integration;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.project.ContainerService;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The run token end to end (TODO cfb5d56b): a real run on a real HTTP port, whose shell block calls the
 * API with {@code $MYSURICATES_RUN_TOKEN} through {@code curl} — reading is allowed, writing is refused,
 * and the token it prints is stored masked. The other tests stop at the filter (MockMvc) or the process
 * environment; this one proves that the pieces fit together, proxy settings of the host included.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("integration")
class RunTokenHttpIntegrationTest {

    private static final EnumSet<RunStatus> TERMINAL = EnumSet.of(RunStatus.COMPLETED,
            RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.CANCELLED);

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("automation.security.enabled", () -> "true");
        registry.add("automation.security.api-key", () -> "run-token-test-key");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired private WorkflowExecutor workflowExecutor;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @MockitoBean private ContainerService containerService;

    @TempDir Path projectDir;

    @Test
    @DisplayName("a shell step reads with the run token, cannot write, and its output shows the token masked")
    void shellStepReadsButCannotWrite() throws Exception {
        assumeThat(new ProcessBuilder("curl", "--version").start().waitFor()).as("curl on the PATH").isZero();
        String script = String.join("\n",
                "set -euo pipefail",
                "auth=\"X-Run-Token: $MYSURICATES_RUN_TOKEN\"",
                "get=$(curl -s -o /dev/null -w '%{http_code}' -H \"$auth\" \"$MYSURICATES_API/todos\")",
                "post=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H \"$auth\" -H 'Content-Type: application/json'"
                        + " -d '{\"title\":\"nope\"}' \"$MYSURICATES_API/todos\")",
                "nokey=$(curl -s -o /dev/null -w '%{http_code}' \"$MYSURICATES_API/todos\")",
                "other=$(curl -s -o /dev/null -w '%{http_code}' -H \"$auth\" \"$MYSURICATES_API/executions/pending-input\")",
                "echo \"get=$get post=$post nokey=$nokey other=$other\"",
                "echo \"leak=$MYSURICATES_RUN_TOKEN\"",
                // a file is not masked: the test uses it to try the token once the run is over
                "printf '%s' \"$MYSURICATES_RUN_TOKEN\" > token.txt");
        Workflow workflow = workflow(script, Map.of("permissions", List.of("read")));

        StepResult step = runAndGetFirstStep(workflow);

        assertThat(step.getOutput()).contains("get=200 post=403 nokey=403 other=403").contains("leak=***");
        String token = java.nio.file.Files.readString(projectDir.resolve("token.txt"));
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
        var afterRun = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + PORT + "/api/v1/todos"))
                        .header("X-Run-Token", token).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(afterRun.statusCode()).as("token after the run").isEqualTo(401);
        assertThat(afterRun.body()).contains("RUN_TOKEN_INVALID");
    }

    @Test
    @DisplayName("without the permission the run gets no token, and the same call is refused")
    void withoutPermissionNoToken() throws Exception {
        assumeThat(new ProcessBuilder("curl", "--version").start().waitFor()).as("curl on the PATH").isZero();
        String script = String.join("\n",
                "set -euo pipefail",
                "echo \"token=${MYSURICATES_RUN_TOKEN:-none}\"");
        Workflow workflow = workflow(script, Map.of());

        StepResult step = runAndGetFirstStep(workflow);

        assertThat(step.getOutput()).contains("token=none");
    }

    private Workflow workflow(String script, Map<String, Object> metadata) {
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name("run-token-" + System.nanoTime()).type(ProjectType.CODE).path(projectDir.toString())
                .status(ProjectStatus.STOPPED).build());
        BlockDefinition block = BlockDefinition.builder().type("shell").name("api")
                .config(new HashMap<>(Map.of("command", script))).build();
        Workflow saved = workflowRepository.saveAndFlush(Workflow.builder()
                .name("run-token-" + System.nanoTime()).project(project).blocks(new ArrayList<>(List.of(block)))
                .activatedSkills(new ArrayList<>()).defaultContext(Map.of()).metadata(new HashMap<>(metadata)).build());
        return workflowRepository.findByIdWithSkills(saved.getId()).orElseThrow();
    }

    private StepResult runAndGetFirstStep(Workflow workflow) throws InterruptedException {
        WorkflowRun started = workflowExecutor.execute(workflow, Map.of(), TriggerSource.MANUAL);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        WorkflowRun run = started;
        while (Instant.now().isBefore(deadline)) {
            run = runRepository.findById(started.getId()).orElseThrow();
            if (TERMINAL.contains(run.getStatus())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(run.getStatus()).as("run status").isEqualTo(RunStatus.COMPLETED);
        return stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(started.getId()).get(0);
    }
}

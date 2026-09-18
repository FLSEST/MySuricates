package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the run-focused system prompt executed <b>without</b> a
 * surrounding transaction — exactly as {@code AssistantService.chat} calls it
 * (OSIV is disabled, no {@code @Transactional} on the assistant path).
 *
 * <p>Deliberately not {@code @Transactional}: a transactional test keeps its own
 * Hibernate session open and would mask the {@code LazyInitializationException}
 * this test exists to catch. The Mockito unit tests cannot catch it — they never
 * produce real lazy proxies.
 *
 * <p>Regression coverage: {@code appendRunSummary} used to navigate the detached
 * {@code run.workflow} proxy; the resulting exception was swallowed and the
 * Status / Run error / Steps lines silently vanished from the diagnosis prompt.
 */
@SpringBootTest
@ActiveProfiles("integration")
class AssistantContextBuilderIntegrationTest {

    @Autowired
    private AssistantContextBuilder contextBuilder;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private com.automation.executor.BlockTypeRegistry blockTypeRegistry;

    private final List<String> createdRunIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdRunIds.forEach(id -> {
            try {
                runRepository.deleteById(id);
            } catch (Exception ignored) {
                // already gone
            }
        });
        createdRunIds.clear();
    }

    /**
     * Pins the block-type source of truth against the wired application (TODO 89dd9e34): the
     * {@code BlockTypeRegistry} bean — fed by the real {@code BlockExecutor} beans, which the
     * classpath-scan variant in {@code BlockTypeListsGuardTest} cannot see — must agree with the
     * hand-maintained assistant list. A new executor that nobody lists fails here.
     */
    @Test
    void blockTypeRegistryBeanMatchesTheAssistantBlockTypeList() {
        assertThat(blockTypeRegistry.knownTypes())
                .as("BlockTypeRegistry bean vs. AssistantToolValidator.KNOWN_BLOCK_TYPES")
                .containsExactlyInAnyOrderElementsOf(AssistantToolValidator.KNOWN_BLOCK_TYPES);
    }

    @Test
    void runFocusedPrompt_failedRun_containsStatusErrorAndTailEvidence() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("diag-it-" + nano)
                .description("Diagnosis IT")
                .type(ProjectType.CODE)
                .path("/tmp/diag-it-" + nano)
                .status(ProjectStatus.STOPPED)
                .build());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("diag-wf-" + nano)
                .description("Failing workflow")
                .project(project)
                .blocks(new ArrayList<>(List.of(
                        BlockDefinition.builder().type("shell")
                                .config(new HashMap<>(Map.of("command", "curl -f https://example.org/health")))
                                .build())))
                .build());

        Instant now = Instant.now();
        String output = "x".repeat(6000) + "curl: (22) The requested URL returned error: 502 Bad Gateway";
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(RunStatus.FAILED)
                .startedAt(now.minusSeconds(60))
                .error(Map.of("step", 0, "blockType", "shell",
                        "message", "Step 0 (shell) failed: ...502 Bad Gateway"))
                .build();
        run.addStep(StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.FAILED)
                .startedAt(now.minusSeconds(60))
                .durationMs(900L)
                .output(output)
                .metadata(new HashMap<>(Map.of("blockType", "shell")))
                .build());
        createdRunIds.add(runRepository.save(run).getId());

        var ctx = new AssistantChatRequest.PageContext("run", run.getId(), null,
                workflow.getId(), run.getId(),
                "/workflows/" + workflow.getId() + "/run/" + run.getId());

        // Called outside any transaction — like AssistantService does.
        String prompt = contextBuilder.buildSystemPrompt(ctx, "", "de");

        assertThat(prompt).contains("Status: FAILED");
        assertThat(prompt).contains("Run error:");
        assertThat(prompt).contains("Failed step 1 (shell)");
        assertThat(prompt).contains("Block command: curl -f https://example.org/health");
        // Tail-biased evidence: the error at the END of the 6k output must survive.
        assertThat(prompt).contains("502 Bad Gateway");
        assertThat(prompt).contains("[... skipped");
        assertThat(prompt).contains("<fetch type=\"step_output\" id=\"" + run.getId() + "/1\"/>");
        // The workflow exists — it must not be reported as deleted.
        assertThat(prompt).doesNotContain("[deleted]");
    }
}

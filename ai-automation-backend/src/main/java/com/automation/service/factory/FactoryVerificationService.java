package com.automation.service.factory;

import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.dto.response.WorkflowFactoryResult;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verify/fix loop of the workflow factory: deploy a workflow to the Test instance, run it,
 * let the model repair the blocks on failure and report the outcome back to Dev. Extracted from
 * {@link WorkflowFactoryService} (f959b3aa). Every repair passes the build check before it is saved
 * ({@link #autoFixWorkflow(Workflow, String, String)}, TODO 99a9dfb0).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FactoryVerificationService {

    private final WorkflowService workflowService;
    private final TestingService testingService;
    private final ObjectMapper objectMapper;
    private final FactoryPromptBuilder promptBuilder;
    private final FactoryResponseParser responseParser;
    private final FactoryGeneratorResolver generatorResolver;
    private final GeneratedWorkflowCheck buildCheck;

    /**
     * Deploys a workflow to the Test instance and verifies it.
     */
    public FactoryTestResult deployAndVerify(Workflow workflow) {
        String projectName = workflow.getProject() != null ? workflow.getProject().getName() : null;

        // Sync to test
        testingService.syncDefinitions();

        // Execute on test
        Map<String, Object> context = workflow.getDefaultContext() != null
                ? new HashMap<>(workflow.getDefaultContext()) : Map.of();

        try {
            String runId = testingService.executeWorkflowOnTest(
                    workflow.getName(), projectName, context);

            // Poll for completion
            JsonNode run = testingService.pollTestRun(runId);
            String status = run.path("status").asText("FAILED");

            String error = null;
            if ("FAILED".equals(status)) {
                JsonNode errorNode = run.path("error");
                if (errorNode.isObject()) {
                    error = errorNode.path("message").asText(null);
                } else if (errorNode.isTextual()) {
                    error = errorNode.asText();
                }
                // Also check step outputs for error details
                if (error == null) {
                    error = responseParser.extractStepErrors(run);
                }
            }

            String testStatus = "COMPLETED".equals(status) ? "PASSED" : "FAILED";
            return new FactoryTestResult(runId, testStatus, error);
        } catch (Exception e) {
            return new FactoryTestResult(null, "FAILED", e.getMessage());
        }
    }

    /**
     * Verifies an existing workflow by deploying and testing it.
     */
    public WorkflowFactoryResult verifyWorkflow(String workflowId) {
        long start = System.currentTimeMillis();
        Workflow workflow = workflowService.findById(workflowId);
        FactoryTestResult testResult = deployAndVerify(workflow);

        reportTestResult(workflow, testResult.status());

        String overallStatus = "PASSED".equals(testResult.status()) ? "VERIFIED" : "FAILED";
        return new WorkflowFactoryResult(
                workflow.getId(), workflow.getName(), overallStatus,
                testResult.runId(), testResult.status(), testResult.error(),
                false, System.currentTimeMillis() - start, List.of()
        );
    }

    /**
     * Verifies a workflow on the Test instance and auto-fixes if it fails.
     * Best-effort: callers should catch exceptions (e.g. test instance not running).
     */
    public FactoryTestResult verifyAndFix(String workflowId, int maxRetries) {
        Workflow workflow = workflowService.findById(workflowId);
        FactoryTestResult result = deployAndVerify(workflow);

        if ("FAILED".equals(result.status()) && maxRetries > 0) {
            for (int i = 0; i < maxRetries && "FAILED".equals(result.status()); i++) {
                log.info("Factory: auto-fix attempt {}/{} for workflow '{}'",
                        i + 1, maxRetries, workflow.getName());
                try {
                    if (!autoFixWorkflow(workflow, result.error()).applied()) {
                        continue; // workflow unchanged — another test run would only repeat the failure
                    }
                    workflow = workflowService.findById(workflowId);
                    result = deployAndVerify(workflow);
                } catch (Exception e) {
                    log.warn("Factory: auto-fix attempt {} failed: {}", i + 1, e.getMessage());
                }
            }
        }

        reportTestResult(workflow, result.status());
        return result;
    }

    /** Reports a test status for the workflow on Dev. */
    public void reportTestResult(Workflow workflow, String status) {
        String projectName = workflow.getProject() != null ? workflow.getProject().getName() : null;
        testingService.reportTestResult(workflow.getName(), projectName, status);
    }

    /**
     * Outcome of one AI repair: {@code applied}, or not — then {@code reason} says why (unusable answer, or discarded
     * by the build check) and the workflow is unchanged.
     */
    public record AutoFixOutcome(boolean applied, String reason) {

        static AutoFixOutcome saved() {
            return new AutoFixOutcome(true, null);
        }

        static AutoFixOutcome notApplied(String reason) {
            return new AutoFixOutcome(false, reason);
        }
    }

    public AutoFixOutcome autoFixWorkflow(Workflow workflow, String errorMessage) throws IOException {
        return autoFixWorkflow(workflow, errorMessage, null);
    }

    /**
     * Lets the model repair the blocks from the error of a failed test run and saves the repair as a new version
     * ({@link ChangeSource#FACTORY}). Before saving, the repair goes through {@link GeneratedWorkflowCheck#reviewRepair}
     * (TODO 99a9dfb0), without another AI call: a repair that changes the structure (block count, top-level type per
     * position, nested blocks per container) or has more HIGH findings than the current version is discarded with a
     * WARN, and the workflow stays as it is. A block's {@code name}, {@code skillIds} and {@code requiredCapabilities}
     * that the repair omits are taken from the current version.
     */
    public AutoFixOutcome autoFixWorkflow(Workflow workflow, String errorMessage, String provider) throws IOException {
        String resolvedProvider = generatorResolver.resolveProvider(provider);
        String blocksJson = objectMapper.writeValueAsString(workflow.getBlocks());

        String prompt = promptBuilder.buildAutoFixPrompt(
                workflow.getName(), workflow.getDescription(), blocksJson, errorMessage);

        WorkflowTextGenerator generator = generatorResolver.generatorFor(resolvedProvider);
        String responseText = generator.generate(prompt,
                "You are a workflow debugging assistant. Output only valid JSON.", 120);
        List<BlockDefinition> fixedBlocks = responseParser.parseBlocksFromArray(responseText);
        if (fixedBlocks.isEmpty()) {
            log.warn("Factory: auto-fix answer for workflow '{}' unusable, workflow unchanged", workflow.getName());
            return AutoFixOutcome.notApplied("Antwort der Reparatur unbrauchbar");
        }

        List<BlockDefinition> current = workflow.getBlocks() != null ? workflow.getBlocks() : List.of();
        GeneratedWorkflowCheck.RepairReview review =
                buildCheck.reviewRepair(current, fixedBlocks, workflow.getInstructions());
        if (!review.accepted()) {
            log.warn("Factory: auto-fix for workflow '{}' discarded by the build check, {}; workflow unchanged",
                    workflow.getName(), review.rejection());
            return AutoFixOutcome.notApplied("Reparatur verworfen, " + review.rejection());
        }
        fixedBlocks = review.blocks();

        workflow.setBlocks(fixedBlocks);
        // Use the repository indirectly through service update
        var updateRequest = new UpdateWorkflowRequest(
                null, null, null, fixedBlocks, null, null, null, null, null
        );
        workflowService.update(workflow.getId(), updateRequest, ChangeSource.FACTORY);
        log.info("Factory: auto-fixed workflow '{}' with {} blocks", workflow.getName(), fixedBlocks.size());
        return AutoFixOutcome.saved();
    }
}

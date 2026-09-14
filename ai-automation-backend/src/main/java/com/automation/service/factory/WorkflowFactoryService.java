package com.automation.service.factory;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.dto.response.WorkflowFactoryResult;
import com.automation.config.FactoryConfig;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * The workflow factory pipeline: generate a workflow from a topic, deploy it to the Test
 * instance, verify, auto-fix on failure and optionally promote it to a template. Prompts come
 * from {@link FactoryPromptBuilder}, model-output parsing from {@link FactoryResponseParser},
 * the topic list from {@link FactoryTopicCatalog}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowFactoryService {

    private final ClaudeWorkflowGenerator claudeGenerator;
    private final CodexWorkflowGenerator codexGenerator;
    private final FactoryConfig factoryConfig;
    private final WorkflowService workflowService;
    private final WorkflowTemplateService templateService;
    private final TestingService testingService;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final FactoryPromptBuilder promptBuilder;
    private final FactoryResponseParser responseParser;

    /**
     * Generates a workflow definition from a topic/description using Claude CLI.
     * Returns the created workflow.
     */
    public Workflow generateWorkflow(WorkflowFactoryRequest request) throws IOException {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));

        String prompt = promptBuilder.buildGeneratorPrompt(request);
        String systemPrompt = promptBuilder.buildSystemPrompt();

        String provider = resolveProvider(request.provider());
        log.info("Factory: generating workflow for topic '{}' (category: {}, provider: {})",
                request.topic(), request.category(), provider);

        WorkflowTextGenerator generator = getGenerator(provider);
        String responseText = generator.generate(prompt, systemPrompt, factoryConfig.getTimeoutSeconds());
        JsonNode workflowJson = responseParser.extractJsonFromResponse(responseText);

        // Build CreateWorkflowRequest from Claude's output
        String name = workflowJson.path("name").asText(request.topic());
        String description = workflowJson.has("description")
                ? workflowJson.path("description").asText() : request.description();
        String icon = workflowJson.path("icon").asText("🔧");
        String instructions = workflowJson.has("instructions")
                ? workflowJson.path("instructions").asText(null) : null;

        List<BlockDefinition> blocks = responseParser.parseBlocks(workflowJson);

        Map<String, Object> defaultContext = workflowJson.has("defaultContext")
                ? objectMapper.convertValue(workflowJson.get("defaultContext"), new TypeReference<>() {})
                : new HashMap<>();

        // Merge test inputs into default context
        if (request.testInputs() != null && !request.testInputs().isEmpty()) {
            defaultContext.putAll(request.testInputs());
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("factoryGenerated", true);
        metadata.put("factoryTopic", request.topic());
        if (request.category() != null) {
            metadata.put("factoryCategory", request.category());
        }

        CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
                project.getId(), name, description, icon, blocks,
                List.of(), instructions, null, null, null, metadata, defaultContext, null
        );

        Workflow workflow = workflowService.create(createRequest, com.automation.entity.ChangeSource.FACTORY);
        log.info("Factory: created workflow '{}' ({})", workflow.getName(), workflow.getId());
        return workflow;
    }

    /**
     * Full pipeline: generate → deploy to test → verify → optionally promote.
     */
    public WorkflowFactoryResult runFactory(WorkflowFactoryRequest request) {
        long start = System.currentTimeMillis();
        List<String> retryErrors = new ArrayList<>();

        try {
            // 1. Generate
            Workflow workflow = generateWorkflow(request);

            // 2. Deploy & verify
            FactoryTestResult testResult = deployAndVerify(workflow);

            // 3. Auto-fix if enabled and failed
            if (request.autoFix() && "FAILED".equals(testResult.status)) {
                int retries = 0;
                while (retries < request.maxRetries() && "FAILED".equals(testResult.status)) {
                    retries++;
                    log.info("Factory: auto-fix attempt {}/{} for workflow '{}'",
                            retries, request.maxRetries(), workflow.getName());
                    try {
                        autoFixWorkflow(workflow, testResult.error, request.provider());
                        // Re-fetch after update
                        workflow = workflowService.findById(workflow.getId());
                        testResult = deployAndVerify(workflow);
                    } catch (Exception e) {
                        retryErrors.add("Retry " + retries + ": " + e.getMessage());
                        log.warn("Factory: auto-fix attempt {} failed: {}", retries, e.getMessage());
                    }
                }
            }

            // 4. Promote if passed and autoPromote enabled
            boolean promoted = false;
            if (request.autoPromote() && "PASSED".equals(testResult.status)) {
                try {
                    templateService.createFromWorkflow(workflow.getId());
                    promoted = true;
                    log.info("Factory: promoted workflow '{}' as template", workflow.getName());
                } catch (Exception e) {
                    log.warn("Factory: failed to promote workflow: {}", e.getMessage());
                }
            }

            // 5. Report test result on Dev
            String projectName = workflow.getProject() != null ? workflow.getProject().getName() : null;
            testingService.reportTestResult(workflow.getName(), projectName, testResult.status);

            String overallStatus = "PASSED".equals(testResult.status) ? "VERIFIED" : "FAILED";
            return new WorkflowFactoryResult(
                    workflow.getId(), workflow.getName(), overallStatus,
                    testResult.runId, testResult.status, testResult.error,
                    promoted, System.currentTimeMillis() - start, retryErrors
            );
        } catch (Exception e) {
            log.error("Factory pipeline failed: {}", e.getMessage(), e);
            return new WorkflowFactoryResult(
                    null, null, "FAILED",
                    null, null, e.getMessage(),
                    false, System.currentTimeMillis() - start, retryErrors
            );
        }
    }

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

        String projectName = workflow.getProject() != null ? workflow.getProject().getName() : null;
        testingService.reportTestResult(workflow.getName(), projectName, testResult.status);

        String overallStatus = "PASSED".equals(testResult.status) ? "VERIFIED" : "FAILED";
        return new WorkflowFactoryResult(
                workflow.getId(), workflow.getName(), overallStatus,
                testResult.runId, testResult.status, testResult.error,
                false, System.currentTimeMillis() - start, List.of()
        );
    }

    /**
     * Promotes a verified workflow as a template.
     */
    public WorkflowFactoryResult promoteToTemplate(String workflowId) {
        long start = System.currentTimeMillis();
        Workflow workflow = workflowService.findById(workflowId);

        templateService.createFromWorkflow(workflowId);
        log.info("Factory: promoted workflow '{}' as template", workflow.getName());

        return new WorkflowFactoryResult(
                workflow.getId(), workflow.getName(), "PROMOTED",
                null, workflow.getTestStatus(), null,
                true, System.currentTimeMillis() - start, List.of()
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
                    autoFixWorkflow(workflow, result.error());
                    workflow = workflowService.findById(workflowId);
                    result = deployAndVerify(workflow);
                } catch (Exception e) {
                    log.warn("Factory: auto-fix attempt {} failed: {}", i + 1, e.getMessage());
                }
            }
        }

        String projectName = workflow.getProject() != null ? workflow.getProject().getName() : null;
        testingService.reportTestResult(workflow.getName(), projectName, result.status());
        return result;
    }

    // ── Internal helpers ──────────────────────────────────

    public record FactoryTestResult(String runId, String status, String error) {}

    public void autoFixWorkflow(Workflow workflow, String errorMessage) throws IOException {
        autoFixWorkflow(workflow, errorMessage, null);
    }

    public void autoFixWorkflow(Workflow workflow, String errorMessage, String provider) throws IOException {
        String resolvedProvider = resolveProvider(provider);
        String blocksJson = objectMapper.writeValueAsString(workflow.getBlocks());

        String prompt = promptBuilder.buildAutoFixPrompt(
                workflow.getName(), workflow.getDescription(), blocksJson, errorMessage);

        WorkflowTextGenerator generator = getGenerator(resolvedProvider);
        String responseText = generator.generate(prompt,
                "You are a workflow debugging assistant. Output only valid JSON.", 120);
        List<BlockDefinition> fixedBlocks = responseParser.parseBlocksFromArray(responseText);

        if (!fixedBlocks.isEmpty()) {
            workflow.setBlocks(fixedBlocks);
            // Use the repository indirectly through service update
            var updateRequest = new com.automation.dto.request.UpdateWorkflowRequest(
                    null, null, null, fixedBlocks, null, null, null, null, null
            );
            workflowService.update(workflow.getId(), updateRequest, com.automation.entity.ChangeSource.FACTORY);
            log.info("Factory: auto-fixed workflow '{}' with {} blocks", workflow.getName(), fixedBlocks.size());
        }
    }

    private WorkflowTextGenerator getGenerator(String provider) {
        return switch (provider.toUpperCase()) {
            case "CLAUDE" -> claudeGenerator;
            case "CODEX" -> codexGenerator;
            default -> throw new IllegalArgumentException(
                    "Unknown factory provider: " + provider + ". Available: [CLAUDE, CODEX]");
        };
    }

    private String resolveProvider(String requestProvider) {
        return (requestProvider != null && !requestProvider.isBlank())
                ? requestProvider.toUpperCase()
                : factoryConfig.getProvider().toUpperCase();
    }
}

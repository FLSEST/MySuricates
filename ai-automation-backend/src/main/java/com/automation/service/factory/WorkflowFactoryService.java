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

    /**
     * Generates a workflow definition from a topic/description using Claude CLI.
     * Returns the created workflow.
     */
    public Workflow generateWorkflow(WorkflowFactoryRequest request) throws IOException {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));

        String prompt = buildGeneratorPrompt(request);
        String systemPrompt = buildSystemPrompt();

        String provider = resolveProvider(request.provider());
        log.info("Factory: generating workflow for topic '{}' (category: {}, provider: {})",
                request.topic(), request.category(), provider);

        WorkflowTextGenerator generator = getGenerator(provider);
        String responseText = generator.generate(prompt, systemPrompt, factoryConfig.getTimeoutSeconds());
        JsonNode workflowJson = extractJsonFromResponse(responseText);

        // Build CreateWorkflowRequest from Claude's output
        String name = workflowJson.path("name").asText(request.topic());
        String description = workflowJson.has("description")
                ? workflowJson.path("description").asText() : request.description();
        String icon = workflowJson.path("icon").asText("\uD83D\uDD27");
        String instructions = workflowJson.has("instructions")
                ? workflowJson.path("instructions").asText(null) : null;

        List<BlockDefinition> blocks = parseBlocks(workflowJson);

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
                List.of(), instructions, null, null, metadata, defaultContext
        );

        Workflow workflow = workflowService.create(createRequest);
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
                    error = extractStepErrors(run);
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

    /**
     * Returns available topic categories.
     */
    public List<Map<String, String>> getTopicCategories() {
        return List.of(
                Map.of("id", "CODE", "name", "Code", "description", "Lint, Test, Review, Refactor, Deploy"),
                Map.of("id", "CONTENT", "name", "Content", "description", "Blog, Docs, Translation, Email, Social"),
                Map.of("id", "DATA", "name", "Data", "description", "CSV → Report, Analyse, Pipeline, ETL"),
                Map.of("id", "RESEARCH", "name", "Research", "description", "Topic Summary, Fact-Check, Compare"),
                Map.of("id", "TRANSFORM", "name", "Transform", "description", "Format, Convert, Migrate, Merge"),
                Map.of("id", "DEVOPS", "name", "DevOps", "description", "CI/CD, Monitor, Backup, Update"),
                Map.of("id", "CREATIVE", "name", "Creative", "description", "Design, Prototype, Generate, Iterate"),
                Map.of("id", "CUSTOM", "name", "Custom", "description", "Free-form description")
        );
    }

    // ── Internal helpers ──────────────────────────────────

    public record FactoryTestResult(String runId, String status, String error) {}

    private void autoFixWorkflow(Workflow workflow, String errorMessage) throws IOException {
        autoFixWorkflow(workflow, errorMessage, null);
    }

    private void autoFixWorkflow(Workflow workflow, String errorMessage, String provider) throws IOException {
        String resolvedProvider = resolveProvider(provider);
        String blocksJson = objectMapper.writeValueAsString(workflow.getBlocks());

        String prompt = """
                A workflow failed during automated testing. Analyze the error and fix the workflow blocks.

                WORKFLOW NAME: %s
                WORKFLOW DESCRIPTION: %s

                CURRENT BLOCKS (JSON):
                %s

                ERROR:
                %s

                Fix the blocks to resolve this error. Output ONLY the corrected blocks array as JSON.
                Do not wrap in markdown code fences.
                """.formatted(workflow.getName(), workflow.getDescription(), blocksJson, errorMessage);

        WorkflowTextGenerator generator = getGenerator(resolvedProvider);
        String responseText = generator.generate(prompt,
                "You are a workflow debugging assistant. Output only valid JSON.", 120);
        List<BlockDefinition> fixedBlocks = parseBlocksFromArray(responseText);

        if (!fixedBlocks.isEmpty()) {
            workflow.setBlocks(fixedBlocks);
            // Use the repository indirectly through service update
            var updateRequest = new com.automation.dto.request.UpdateWorkflowRequest(
                    null, null, null, fixedBlocks, null, null, null, null
            );
            workflowService.update(workflow.getId(), updateRequest);
            log.info("Factory: auto-fixed workflow '{}' with {} blocks", workflow.getName(), fixedBlocks.size());
        }
    }

    private String buildSystemPrompt() {
        return """
                You are a workflow architect for MySuricates, an AI-powered workflow automation platform.
                You create workflow definitions as JSON.

                Available block types:
                - simple: AI-powered tasks (analysis, summarization, generation). Config: { "command": "..." }
                - shell: Execute shell commands. Config: { "command": "..." }
                - retry: Wraps a command with retry logic. Config: { "command": "...", "max_retries": 3, "delay_seconds": 5 }
                - conditional: Branch based on condition. Config: { "condition": "...", "if_true": "...", "if_false": "..." }
                - forEach: Loop over items. Config: { "items_expression": "...", "item_variable": "item", "command": "..." }
                - agentic: Complex multi-step AI task with goal. Config: { "goal": "...", "max_iterations": 5 }

                Rules:
                - Use {{ variable_name }} syntax for variables
                - Use {{ previous_output }} to pass data between steps
                - Shell blocks for concrete commands (npm, git, curl, find, etc.)
                - Simple blocks for AI tasks (analysis, summarization, generation)
                - Retry blocks when failures are likely (API calls, network, tests)
                - Agentic blocks only for complex, multi-step autonomous tasks
                - Keep workflows practical and focused (3-7 blocks typical)

                Output ONLY valid JSON (no markdown fences).
                """;
    }

    private String buildGeneratorPrompt(WorkflowFactoryRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Create a workflow for the following topic.\n\n");
        sb.append("TOPIC: ").append(request.topic()).append("\n");
        if (request.category() != null) {
            sb.append("CATEGORY: ").append(request.category()).append("\n");
        }
        if (request.description() != null) {
            sb.append("DESCRIPTION: ").append(request.description()).append("\n");
        }
        if (request.testInputs() != null && !request.testInputs().isEmpty()) {
            sb.append("TEST INPUTS: ").append(request.testInputs()).append("\n");
        }
        sb.append("""

                Output JSON with this structure:
                {
                  "name": "Short workflow name",
                  "description": "What the workflow does",
                  "icon": "fitting emoji",
                  "blocks": [
                    { "type": "simple|shell|retry|conditional|forEach|agentic", "config": { "command": "..." } }
                  ],
                  "defaultContext": { "variable": "default_value" },
                  "instructions": "Workflow-specific instructions for the AI agent",
                  "testInputs": { "variable": "test_value" }
                }
                """);
        return sb.toString();
    }

    private JsonNode extractJsonFromResponse(String response) throws IOException {
        // Try to find JSON object in the response
        String trimmed = response.trim();

        // Remove markdown code fences if present
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }

        // Find first { and last }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = trimmed.substring(start, end + 1);
            return objectMapper.readTree(json);
        }

        throw new IOException("No valid JSON found in Claude response");
    }

    private List<BlockDefinition> parseBlocks(JsonNode workflowJson) {
        List<BlockDefinition> blocks = new ArrayList<>();
        JsonNode blocksNode = workflowJson.path("blocks");
        if (blocksNode.isArray()) {
            for (JsonNode blockNode : blocksNode) {
                BlockDefinition block = new BlockDefinition();
                block.setType(blockNode.path("type").asText("simple"));
                if (blockNode.has("config")) {
                    Map<String, Object> config = objectMapper.convertValue(
                            blockNode.get("config"), new TypeReference<>() {});
                    block.setConfig(config);
                }
                if (blockNode.has("requiredCapabilities")) {
                    List<String> caps = objectMapper.convertValue(
                            blockNode.get("requiredCapabilities"), new TypeReference<>() {});
                    block.setRequiredCapabilities(caps);
                }
                blocks.add(block);
            }
        }
        return blocks;
    }

    private List<BlockDefinition> parseBlocksFromArray(String response) {
        try {
            String trimmed = response.trim();
            // Remove markdown code fences
            if (trimmed.startsWith("```")) {
                int firstNewline = trimmed.indexOf('\n');
                int lastFence = trimmed.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
                }
            }

            // Find JSON array or object
            int arrStart = trimmed.indexOf('[');
            int objStart = trimmed.indexOf('{');

            if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
                // Direct array
                int arrEnd = trimmed.lastIndexOf(']');
                String json = trimmed.substring(arrStart, arrEnd + 1);
                JsonNode arrayNode = objectMapper.readTree(json);
                List<BlockDefinition> blocks = new ArrayList<>();
                for (JsonNode blockNode : arrayNode) {
                    BlockDefinition block = new BlockDefinition();
                    block.setType(blockNode.path("type").asText("simple"));
                    if (blockNode.has("config")) {
                        block.setConfig(objectMapper.convertValue(blockNode.get("config"), new TypeReference<>() {}));
                    }
                    blocks.add(block);
                }
                return blocks;
            } else if (objStart >= 0) {
                // Wrapped in object with "blocks" key
                JsonNode obj = extractJsonFromResponse(trimmed);
                return parseBlocks(obj);
            }
        } catch (Exception e) {
            log.warn("Failed to parse blocks from auto-fix response: {}", e.getMessage());
        }
        return List.of();
    }

    private String extractStepErrors(JsonNode run) {
        JsonNode steps = run.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                if ("FAILED".equals(step.path("status").asText())) {
                    String error = step.path("error").asText(null);
                    if (error != null) return error;
                    String output = step.path("output").asText(null);
                    if (output != null && output.length() < 500) return output;
                }
            }
        }
        return null;
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

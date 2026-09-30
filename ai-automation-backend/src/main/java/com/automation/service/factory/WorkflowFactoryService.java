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
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
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
 * the topic list from {@link FactoryTopicCatalog}, the verify/fix loop from
 * {@link FactoryVerificationService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowFactoryService {

    private final FactoryGeneratorResolver generatorResolver;
    private final FactoryConfig factoryConfig;
    private final WorkflowService workflowService;
    private final WorkflowTemplateService templateService;
    private final FactoryVerificationService verificationService;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final FactoryPromptBuilder promptBuilder;
    private final FactoryResponseParser responseParser;
    private final GeneratedWorkflowCheck buildCheck;

    /**
     * Generates a workflow definition from a topic/description using Claude CLI.
     * Returns the created workflow.
     */
    public Workflow generateWorkflow(WorkflowFactoryRequest request) throws IOException {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));

        String prompt = promptBuilder.buildGeneratorPrompt(request);
        String systemPrompt = promptBuilder.buildSystemPrompt();

        String provider = generatorResolver.resolveProvider(request.provider());
        log.info("Factory: generating workflow for topic '{}' (category: {}, provider: {})",
                request.topic(), request.category(), provider);

        WorkflowTextGenerator generator = generatorResolver.generatorFor(provider);
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

        // Build check before any test run (TODO 326d5987): HIGH findings get exactly one correction round
        // with the same generator — the cheap check runs before the expensive verify/fix loop.
        GeneratedWorkflowCheck.Correction check = buildCheck.correctOnce(blocks, instructions,
                correctionPrompt -> generator.generate(correctionPrompt, systemPrompt, factoryConfig.getTimeoutSeconds()));
        blocks = check.blocks();

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
        buildCheck.reportRemaining(workflow.getId(), workflow.getName(), "Factory", check.remaining());
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
            FactoryTestResult testResult = verificationService.deployAndVerify(workflow);

            // 3. Auto-fix if enabled and failed
            if (request.autoFix() && "FAILED".equals(testResult.status())) {
                int retries = 0;
                while (retries < request.maxRetries() && "FAILED".equals(testResult.status())) {
                    retries++;
                    log.info("Factory: auto-fix attempt {}/{} for workflow '{}'",
                            retries, request.maxRetries(), workflow.getName());
                    try {
                        FactoryVerificationService.AutoFixOutcome fix =
                                verificationService.autoFixWorkflow(workflow, testResult.error(), request.provider());
                        if (!fix.applied()) {
                            // Workflow unchanged — another test run would only repeat the failure.
                            retryErrors.add("Retry " + retries + ": " + fix.reason());
                            continue;
                        }
                        // Re-fetch after update
                        workflow = workflowService.findById(workflow.getId());
                        testResult = verificationService.deployAndVerify(workflow);
                    } catch (Exception e) {
                        retryErrors.add("Retry " + retries + ": " + e.getMessage());
                        log.warn("Factory: auto-fix attempt {} failed: {}", retries, e.getMessage());
                    }
                }
            }

            // 4. Promote if passed and autoPromote enabled
            boolean promoted = false;
            if (request.autoPromote() && "PASSED".equals(testResult.status())) {
                try {
                    templateService.createFromWorkflow(workflow.getId());
                    promoted = true;
                    log.info("Factory: promoted workflow '{}' as template", workflow.getName());
                } catch (Exception e) {
                    log.warn("Factory: failed to promote workflow: {}", e.getMessage());
                }
            }

            // 5. Report test result on Dev
            verificationService.reportTestResult(workflow, testResult.status());

            String overallStatus = "PASSED".equals(testResult.status()) ? "VERIFIED" : "FAILED";
            var findings = buildCheck.findings(workflow);
            return new WorkflowFactoryResult(
                    workflow.getId(), workflow.getName(), overallStatus,
                    testResult.runId(), testResult.status(), testResult.error(),
                    promoted, System.currentTimeMillis() - start, retryErrors,
                    GeneratedWorkflowCheck.summaryLines(findings),
                    GeneratedWorkflowCheck.summaryLines(findings, "en")
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
}

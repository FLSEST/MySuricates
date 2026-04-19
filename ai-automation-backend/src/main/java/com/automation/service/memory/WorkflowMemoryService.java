package com.automation.service.memory;

import com.automation.entity.StepResult;
import com.automation.entity.WorkflowMemory;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class WorkflowMemoryService {

    private static final String MEMORY_FILE_PATH = ".claude/rules/project-memory.md";

    private final WorkflowMemoryRepository memoryRepository;
    private final WorkflowRunRepository runRepository;
    private final ConversationPreprocessor preprocessor;
    private final ClaudeSummarizer claudeSummarizer;
    private final CodexSummarizer codexSummarizer;
    private final ObjectMapper objectMapper;

    public WorkflowMemoryService(WorkflowMemoryRepository memoryRepository,
                                  WorkflowRunRepository runRepository,
                                  ConversationPreprocessor preprocessor,
                                  ClaudeSummarizer claudeSummarizer,
                                  CodexSummarizer codexSummarizer,
                                  ObjectMapper objectMapper) {
        this.memoryRepository = memoryRepository;
        this.runRepository = runRepository;
        this.preprocessor = preprocessor;
        this.claudeSummarizer = claudeSummarizer;
        this.codexSummarizer = codexSummarizer;
        this.objectMapper = objectMapper;
    }

    public List<WorkflowMemory> findByWorkflowId(String workflowId) {
        return memoryRepository.findByWorkflowIdOrderByCreatedAtDesc(workflowId);
    }

    /**
     * Extracts knowledge from workflow runs and stores it as a new memory version.
     */
    @Transactional
    public WorkflowMemory extractKnowledge(String workflowId, List<String> runIds, String provider) throws IOException {
        // Load runs
        List<WorkflowRun> runs;
        if (runIds != null && !runIds.isEmpty()) {
            runs = runIds.stream()
                    .map(id -> runRepository.findById(id)
                            .orElseThrow(() -> new EntityNotFoundException("Run not found: " + id)))
                    .toList();
        } else {
            runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);
            if (runs.isEmpty()) {
                throw new IllegalStateException("No runs found for workflow: " + workflowId);
            }
            // Take the most recent 10 runs
            runs = runs.subList(0, Math.min(10, runs.size()));
        }

        // Collect all step results
        List<StepResult> allSteps = runs.stream()
                .flatMap(run -> run.getSteps().stream())
                .toList();

        if (allSteps.isEmpty()) {
            throw new IllegalStateException("No step results found in selected runs");
        }

        // Preprocess
        String preprocessed = preprocessor.preprocess(allSteps);
        log.info("Preprocessed {} steps from {} runs into {} chars", allSteps.size(), runs.size(), preprocessed.length());

        // Summarize
        KnowledgeSummarizer summarizer = getSummarizer(provider);
        KnowledgeSummarizer.SummarizationResult result = summarizer.summarize(preprocessed);

        // Store
        List<String> sourceRunIdsList = runs.stream().map(WorkflowRun::getId).toList();

        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId(workflowId)
                .title(result.title())
                .content(result.content())
                .tags(toJson(result.tags()))
                .sourceRunIds(toJson(sourceRunIdsList))
                .provider(provider.toUpperCase())
                .active(false)
                .build();

        return memoryRepository.save(memory);
    }

    /**
     * Extracts knowledge and automatically activates the result.
     * Used by auto-memory extraction after successful runs.
     */
    @Transactional
    public WorkflowMemory extractAndActivate(String workflowId, String provider) throws IOException {
        WorkflowMemory memory = extractKnowledge(workflowId, null, provider);
        memoryRepository.deactivateAllForWorkflow(workflowId);
        memory.setActive(true);
        memoryRepository.save(memory);
        log.info("Auto-extracted and activated memory '{}' for workflow {}", memory.getTitle(), workflowId);
        return memory;
    }

    /**
     * Activates a specific memory version and deactivates all others for the same workflow.
     */
    @Transactional
    public void activate(String workflowId, String memoryId) {
        WorkflowMemory memory = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new EntityNotFoundException("Memory not found: " + memoryId));

        if (!memory.getWorkflowId().equals(workflowId)) {
            throw new IllegalArgumentException("Memory does not belong to workflow");
        }

        memoryRepository.deactivateAllForWorkflow(workflowId);
        memory.setActive(true);
        memoryRepository.save(memory);
    }

    /**
     * Updates a memory entry's title and content.
     */
    @Transactional
    public WorkflowMemory update(String workflowId, String memoryId, String title, String content) {
        WorkflowMemory memory = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new EntityNotFoundException("Memory not found: " + memoryId));

        if (!memory.getWorkflowId().equals(workflowId)) {
            throw new IllegalArgumentException("Memory does not belong to workflow");
        }

        if (title != null) memory.setTitle(title);
        if (content != null) memory.setContent(content);

        return memoryRepository.save(memory);
    }

    @Transactional
    public void delete(String workflowId, String memoryId) {
        WorkflowMemory memory = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new EntityNotFoundException("Memory not found: " + memoryId));

        if (!memory.getWorkflowId().equals(workflowId)) {
            throw new IllegalArgumentException("Memory does not belong to workflow");
        }

        memoryRepository.delete(memory);
    }

    /**
     * Writes the active memory for a workflow to the project's .claude/rules directory.
     * Called before workflow execution so the executing agent can read it.
     */
    public void writeActiveMemoryToProject(String workflowId, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("No project path — skipping memory file write");
            return;
        }

        Path memoryFile = Path.of(projectPath, MEMORY_FILE_PATH);

        Optional<WorkflowMemory> activeMemory = memoryRepository.findByWorkflowIdAndActiveTrue(workflowId);

        if (activeMemory.isPresent()) {
            try {
                Files.createDirectories(memoryFile.getParent());
                Files.writeString(memoryFile, activeMemory.get().getContent());
                log.info("Wrote workflow memory to {}", memoryFile);
            } catch (IOException e) {
                log.warn("Failed to write memory file to {}: {}", memoryFile, e.getMessage());
            }
        } else {
            // Clean up stale memory file from previous runs
            try {
                Files.deleteIfExists(memoryFile);
            } catch (IOException e) {
                log.debug("Failed to delete stale memory file: {}", e.getMessage());
            }
        }
    }

    private KnowledgeSummarizer getSummarizer(String provider) {
        return switch (provider.toUpperCase()) {
            case "CLAUDE" -> claudeSummarizer;
            case "CODEX" -> codexSummarizer;
            default -> throw new IllegalArgumentException(
                    "Unknown provider: " + provider + ". Available: [CLAUDE, CODEX]");
        };
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}

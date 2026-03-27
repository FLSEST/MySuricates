package com.automation.mapper;

import com.automation.dto.response.ChainResponse;
import com.automation.dto.response.ChainRunResponse;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ChainMapper {

    private final WorkflowRepository workflowRepository;
    private final ChainRunRepository chainRunRepository;

    public ChainMapper(WorkflowRepository workflowRepository, ChainRunRepository chainRunRepository) {
        this.workflowRepository = workflowRepository;
        this.chainRunRepository = chainRunRepository;
    }

    public ChainResponse toResponse(WorkflowChain chain) {
        long runCount = chainRunRepository.countByChainId(chain.getId());
        List<ChainResponse.ChainEntryResponse> entryResponses = chain.getEntries().stream()
                .map(this::toEntryResponse)
                .toList();

        return new ChainResponse(
                chain.getId(),
                chain.getName(),
                chain.getDescription(),
                chain.getIcon(),
                entryResponses,
                chain.isStopOnFailure(),
                runCount,
                chain.getMetadata(),
                chain.getCreatedAt(),
                chain.getUpdatedAt()
        );
    }

    public List<ChainResponse> toResponseList(List<WorkflowChain> chains) {
        return chains.stream().map(this::toResponse).toList();
    }

    private ChainResponse.ChainEntryResponse toEntryResponse(ChainEntry entry) {
        String workflowName = null;
        String projectName = null;
        var workflow = workflowRepository.findById(entry.getWorkflowId()).orElse(null);
        if (workflow != null) {
            workflowName = workflow.getName();
            if (workflow.getProject() != null) {
                projectName = workflow.getProject().getName();
            }
        }

        return new ChainResponse.ChainEntryResponse(
                entry.getWorkflowId(),
                workflowName,
                projectName,
                entry.getLabel(),
                entry.getSortOrder(),
                entry.isStopOnFailure(),
                entry.getInputContext()
        );
    }

    public ChainRunResponse toRunResponse(ChainRun run) {
        String chainName = run.getChain() != null ? run.getChain().getName() : null;
        List<ChainRunResponse.ChainRunEntryResponse> entryResponses = run.getEntryRuns().stream()
                .map(e -> new ChainRunResponse.ChainRunEntryResponse(
                        e.getEntryIndex(),
                        e.getWorkflowId(),
                        e.getWorkflowName(),
                        e.getProjectName(),
                        e.getWorkflowRunId(),
                        e.getStatus(),
                        e.getStartedAt(),
                        e.getCompletedAt(),
                        e.getDurationMs()
                ))
                .toList();

        return new ChainRunResponse(
                run.getId(),
                run.getChain() != null ? run.getChain().getId() : null,
                chainName,
                run.getStatus(),
                entryResponses,
                run.getInputContext(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getDurationMs(),
                run.getError(),
                run.getMetadata(),
                run.getTriggeredByScheduleId()
        );
    }

    public List<ChainRunResponse> toRunResponseList(List<ChainRun> runs) {
        return runs.stream().map(this::toRunResponse).toList();
    }
}

package com.automation.mapper;

import com.automation.dto.response.WishResponse;
import com.automation.entity.Wish;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WishMapper {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;

    public WishMapper(ProjectRepository projectRepository, WorkflowRepository workflowRepository) {
        this.projectRepository = projectRepository;
        this.workflowRepository = workflowRepository;
    }

    public WishResponse toResponse(Wish wish) {
        String projectName = null;
        if (wish.getProjectId() != null) {
            projectName = projectRepository.findById(wish.getProjectId())
                    .map(p -> p.getName())
                    .orElse(null);
        }

        List<String> workflowNames = new ArrayList<>();
        if (wish.getGeneratedWorkflowIds() != null) {
            for (String wfId : wish.getGeneratedWorkflowIds()) {
                workflowNames.add(
                        workflowRepository.findById(wfId)
                                .map(Workflow::getName)
                                .orElse(wfId)
                );
            }
        }

        List<String> referenceNames = new ArrayList<>();
        if (wish.getReferenceWorkflowIds() != null) {
            for (String wfId : wish.getReferenceWorkflowIds()) {
                referenceNames.add(
                        workflowRepository.findById(wfId)
                                .map(Workflow::getName)
                                .orElse(wfId)
                );
            }
        }

        return new WishResponse(
                wish.getId(),
                wish.getOriginalText(),
                wish.getStatus(),
                wish.getProjectId(),
                projectName,
                wish.getDecomposition(),
                wish.getGeneratedWorkflowIds(),
                workflowNames,
                wish.getReferenceWorkflowIds(),
                referenceNames,
                wish.getGeneratedChainId(),
                wish.getIterationCount(),
                wish.getMaxIterations(),
                wish.getCurrentRunId(),
                wish.getResultSummary(),
                wish.getError(),
                wish.getIterationHistory(),
                wish.getMetadata(),
                wish.getCreatedAt(),
                wish.getUpdatedAt()
        );
    }

    public List<WishResponse> toResponseList(List<Wish> wishes) {
        return wishes.stream().map(this::toResponse).toList();
    }
}

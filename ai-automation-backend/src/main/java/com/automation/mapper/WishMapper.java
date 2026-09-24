package com.automation.mapper;

import com.automation.dto.response.WishResponse;
import com.automation.entity.Wish;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WishMapper {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;

    public WishMapper(ProjectRepository projectRepository, WorkflowRepository workflowRepository) {
        this.projectRepository = projectRepository;
        this.workflowRepository = workflowRepository;
    }

    public WishResponse toResponse(Wish wish) {
        return toResponseList(List.of(wish)).get(0);
    }

    /**
     * Resolves project and workflow names for all wishes in at most two queries — GET /wishes
     * used to look up the project and every generated/reference workflow one by one (TODO 5bf5a9c8).
     */
    public List<WishResponse> toResponseList(List<Wish> wishes) {
        if (wishes.isEmpty()) return List.of();
        Set<String> projectIds = new HashSet<>();
        Set<String> workflowIds = new HashSet<>();
        for (Wish wish : wishes) {
            if (wish.getProjectId() != null) projectIds.add(wish.getProjectId());
            if (wish.getGeneratedWorkflowIds() != null) workflowIds.addAll(wish.getGeneratedWorkflowIds());
            if (wish.getReferenceWorkflowIds() != null) workflowIds.addAll(wish.getReferenceWorkflowIds());
        }
        Map<String, String> projectNames = projectIds.isEmpty()
                ? Map.of() : namesById(projectRepository.findIdAndNameByIdIn(projectIds));
        // Root query on Workflow: soft-deleted workflows are missing and fall back to their id, as before.
        Map<String, String> workflowNames = workflowIds.isEmpty()
                ? Map.of() : namesById(workflowRepository.findIdAndNameByIdIn(workflowIds));
        return wishes.stream().map(wish -> toResponse(wish, projectNames, workflowNames)).toList();
    }

    private static Map<String, String> namesById(List<Object[]> rows) {
        Map<String, String> names = new HashMap<>();
        for (Object[] row : rows) {
            if (row[1] != null) names.put((String) row[0], (String) row[1]);
        }
        return names;
    }

    private static List<String> namesOrIds(List<String> ids, Map<String, String> names) {
        if (ids == null) return new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        for (String id : ids) resolved.add(names.getOrDefault(id, id));
        return resolved;
    }

    private WishResponse toResponse(Wish wish, Map<String, String> projectNames, Map<String, String> workflowNameById) {
        String projectName = wish.getProjectId() == null ? null : projectNames.get(wish.getProjectId());
        List<String> workflowNames = namesOrIds(wish.getGeneratedWorkflowIds(), workflowNameById);
        List<String> referenceNames = namesOrIds(wish.getReferenceWorkflowIds(), workflowNameById);

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
                wish.getUpdatedAt(),
                wish.isAutoRefine()
        );
    }
}

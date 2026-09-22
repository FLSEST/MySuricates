package com.automation.mapper;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.response.ProjectResponse;
import com.automation.entity.Project;
import com.automation.repository.WorkflowRepository;
import org.mapstruct.Context;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", uses = {ContainerMapper.class})
public abstract class ProjectMapper {

    @Autowired
    protected WorkflowRepository workflowRepository;

    /** Single project: one count query for this project. Soft-deleted workflows do not count. */
    @Mapping(target = "workflowCount", expression = "java(workflowRepository.countByProjectId(project.getId()))")
    @Named("single")
    public abstract ProjectResponse toResponse(Project project);

    // --- Batch variant: workflow counts injected via @Context (avoids a count query per project) ---

    @Mapping(target = "workflowCount", expression = "java(workflowCounts.getOrDefault(project.getId(), 0L))")
    @Named("withWorkflowCounts")
    public abstract ProjectResponse toResponse(Project project, @Context Map<String, Long> workflowCounts);

    @IterableMapping(qualifiedByName = "withWorkflowCounts")
    public abstract List<ProjectResponse> toResponseList(List<Project> projects, @Context Map<String, Long> workflowCounts);

    /**
     * List with workflow counts from ONE GROUP-BY query (instead of one count per project).
     * Values are identical to the single path: soft-deleted workflows are excluded by
     * {@code @SQLRestriction} on {@code Workflow}.
     */
    public List<ProjectResponse> toResponseList(List<Project> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        Map<String, Long> workflowCounts = workflowRepository.countWorkflowsGroupedByProject().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));
        return toResponseList(projects, workflowCounts);
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "STOPPED")
    @Mapping(target = "container", ignore = true)
    @Mapping(target = "workflows", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    public abstract Project toEntity(CreateProjectRequest request);
}

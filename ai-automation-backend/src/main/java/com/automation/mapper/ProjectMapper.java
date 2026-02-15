package com.automation.mapper;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.response.ProjectResponse;
import com.automation.entity.Project;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = {ContainerMapper.class})
public interface ProjectMapper {

    ProjectResponse toResponse(Project project);

    List<ProjectResponse> toResponseList(List<Project> projects);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "STOPPED")
    @Mapping(target = "container", ignore = true)
    @Mapping(target = "workflows", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Project toEntity(CreateProjectRequest request);
}

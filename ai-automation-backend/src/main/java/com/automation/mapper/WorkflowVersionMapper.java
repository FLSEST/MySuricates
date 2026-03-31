package com.automation.mapper;

import com.automation.dto.response.WorkflowVersionResponse;
import com.automation.entity.WorkflowVersion;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface WorkflowVersionMapper {

    WorkflowVersionResponse toResponse(WorkflowVersion version);

    List<WorkflowVersionResponse> toResponseList(List<WorkflowVersion> versions);
}

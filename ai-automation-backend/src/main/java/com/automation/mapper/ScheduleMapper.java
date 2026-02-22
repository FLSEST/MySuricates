package com.automation.mapper;

import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.ScheduledWorkflow;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ScheduleMapper {

    @Mapping(source = "workflow.id", target = "workflowId")
    @Mapping(source = "workflow.name", target = "workflowName")
    @Mapping(source = "workflow.project.name", target = "projectName")
    ScheduledWorkflowResponse toResponse(ScheduledWorkflow scheduled);

    List<ScheduledWorkflowResponse> toResponseList(List<ScheduledWorkflow> scheduled);
}

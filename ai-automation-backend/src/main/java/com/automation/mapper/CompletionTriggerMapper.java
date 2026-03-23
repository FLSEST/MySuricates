package com.automation.mapper;

import com.automation.dto.response.CompletionTriggerResponse;
import com.automation.entity.CompletionTrigger;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CompletionTriggerMapper {

    @Mapping(source = "sourceWorkflow.id", target = "sourceWorkflowId")
    @Mapping(source = "sourceWorkflow.name", target = "sourceWorkflowName")
    @Mapping(source = "targetWorkflow.id", target = "targetWorkflowId")
    @Mapping(source = "targetWorkflow.name", target = "targetWorkflowName")
    CompletionTriggerResponse toResponse(CompletionTrigger trigger);

    List<CompletionTriggerResponse> toResponseList(List<CompletionTrigger> triggers);
}

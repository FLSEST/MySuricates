package com.automation.mapper;

import com.automation.dto.response.TodoTriggerResponse;
import com.automation.entity.TodoTrigger;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TodoTriggerMapper {

    @Mapping(source = "targetWorkflow.id", target = "targetWorkflowId")
    @Mapping(source = "targetWorkflow.name", target = "targetWorkflowName")
    TodoTriggerResponse toResponse(TodoTrigger trigger);

    List<TodoTriggerResponse> toResponseList(List<TodoTrigger> triggers);
}

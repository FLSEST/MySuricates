package com.automation.mapper;

import com.automation.dto.response.TodoTriggerResponse;
import com.automation.entity.TodoTrigger;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TodoTriggerMapper {

    @Mapping(target = "targetWorkflowId", expression = "java(trigger.getTargetWorkflow() != null ? trigger.getTargetWorkflow().getId() : null)")
    @Mapping(target = "targetWorkflowName", expression = "java(trigger.getTargetWorkflow() != null ? trigger.getTargetWorkflow().getName() : \"[gelöscht]\")")
    TodoTriggerResponse toResponse(TodoTrigger trigger);

    List<TodoTriggerResponse> toResponseList(List<TodoTrigger> triggers);
}

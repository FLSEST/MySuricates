package com.automation.mapper;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.WorkflowTemplate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface WorkflowTemplateMapper {

    @Mapping(target = "verifications", ignore = true)
    WorkflowTemplateResponse toResponse(WorkflowTemplate template);

    List<WorkflowTemplateResponse> toResponseList(List<WorkflowTemplate> templates);
}

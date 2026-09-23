package com.automation.mapper;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.WorkflowTemplate;
import com.automation.executor.BlockTypeModules;
import com.automation.executor.ModelUse;
import com.automation.modules.ModuleCatalog;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", imports = {BlockTypeModules.class, ModelUse.class, ModuleCatalog.class})
public interface WorkflowTemplateMapper {

    @Mapping(target = "verifications", ignore = true)
    @Mapping(target = "requiredModules",
            expression = "java(BlockTypeModules.requiredBy(template.getBlocks()).stream().map(ModuleCatalog::key).toList())")
    @Mapping(target = "needsAi", expression = "java(ModelUse.needsAi(template.getBlocks()))")
    WorkflowTemplateResponse toResponse(WorkflowTemplate template);

    List<WorkflowTemplateResponse> toResponseList(List<WorkflowTemplate> templates);
}

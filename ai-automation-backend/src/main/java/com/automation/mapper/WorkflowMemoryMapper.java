package com.automation.mapper;

import com.automation.dto.response.WorkflowMemoryResponse;
import com.automation.entity.WorkflowMemory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring")
public interface WorkflowMemoryMapper {

    @org.mapstruct.Mapping(source = "tags", target = "tags", qualifiedByName = "jsonToList")
    @org.mapstruct.Mapping(source = "sourceRunIds", target = "sourceRunIds", qualifiedByName = "jsonToList")
    WorkflowMemoryResponse toResponse(WorkflowMemory memory);

    List<WorkflowMemoryResponse> toResponseList(List<WorkflowMemory> memories);

    @Named("jsonToList")
    static List<String> jsonToList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}

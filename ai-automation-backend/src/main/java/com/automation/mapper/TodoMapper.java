package com.automation.mapper;

import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring")
public interface TodoMapper {

    @Mapping(source = "tags", target = "tags", qualifiedByName = "jsonToList")
    @Mapping(target = "projectName", ignore = true)
    @Mapping(target = "linkedWorkflowName", ignore = true)
    @Mapping(target = "planning", ignore = true)
    TodoResponse toResponse(Todo todo);

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

    @Named("listToJson")
    static String listToJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return new ObjectMapper().writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}

package com.automation.integration.scenario;

import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.mapper.TodoMapper;
import com.automation.repository.TodoRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Scenario key {@code todos}: TODOs in the scenario project before the run (TODO cc0eb3ba). The runner cleans up. */
final class ScenarioTodos {

    private ScenarioTodos() {
    }

    static void create(TodoRepository todoRepository, Project project, JsonNode spec) {
        for (JsonNode todo : spec) {
            List<String> tags = new ArrayList<>();
            todo.path("tags").forEach(tag -> tags.add(tag.asText()));
            todoRepository.saveAndFlush(Todo.builder()
                    .title(todo.path("title").asText())
                    .description(todo.has("description") ? todo.path("description").asText() : null)
                    .status(Todo.Status.valueOf(todo.path("status").asText("OPEN")))
                    .priority(Todo.Priority.valueOf(todo.path("priority").asText("MEDIUM")))
                    .source(Todo.Source.WORKFLOW)
                    .tags(TodoMapper.listToJson(tags))
                    .projectId(project.getId())
                    .build());
        }
    }
}

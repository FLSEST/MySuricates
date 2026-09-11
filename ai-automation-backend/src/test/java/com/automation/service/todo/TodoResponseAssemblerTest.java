package com.automation.service.todo;

import com.automation.dto.response.TodoResponse;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.mapper.TodoMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoResponseAssemblerTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private TodoMapper todoMapper;
    @InjectMocks private TodoResponseAssembler assembler;

    private static TodoResponse base(String id) {
        return new TodoResponse(id, "t", null, Todo.Status.OPEN, Todo.Priority.MEDIUM, List.of(),
                null, null, null, null, Todo.Source.USER, null, null, null, null, null,
                null, null, null, 0, null, null, null, null, null);
    }

    @Test
    void toResponse_fillsProjectAndWorkflowNames() {
        Todo todo = Todo.builder().id("t-1").projectId("p-1").linkedWorkflowId("wf-1").build();
        when(todoMapper.toResponse(todo)).thenReturn(base("t-1"));
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(Project.builder().id("p-1").name("Proj").build()));
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(Workflow.builder().id("wf-1").name("Fix").build()));

        TodoResponse response = assembler.toResponse(todo);

        assertThat(response.projectName()).isEqualTo("Proj");
        assertThat(response.linkedWorkflowName()).isEqualTo("Fix");
    }

    @Test
    void toResponses_batchLoadsNamesWithOneQueryPerTable() {
        Todo a = Todo.builder().id("a").projectId("p-1").build();
        Todo b = Todo.builder().id("b").projectId("p-1").linkedWorkflowId("wf-1").build();
        when(todoMapper.toResponse(a)).thenReturn(base("a"));
        when(todoMapper.toResponse(b)).thenReturn(base("b"));
        when(projectRepository.findAllById(Set.of("p-1"))).thenReturn(List.of(Project.builder().id("p-1").name("Proj").build()));
        when(workflowRepository.findAllById(Set.of("wf-1"))).thenReturn(List.of(Workflow.builder().id("wf-1").name("Fix").build()));

        List<TodoResponse> responses = assembler.toResponses(List.of(a, b));

        assertThat(responses).extracting(TodoResponse::projectName).containsExactly("Proj", "Proj");
        assertThat(responses.get(1).linkedWorkflowName()).isEqualTo("Fix");
        verify(projectRepository, never()).findById(any());
    }

    @Test
    void toResponses_withoutIds_skipsLookups() {
        Todo a = Todo.builder().id("a").build();
        when(todoMapper.toResponse(a)).thenReturn(base("a"));

        assembler.toResponses(List.of(a));

        verify(projectRepository, never()).findAllById(any());
        verify(workflowRepository, never()).findAllById(any());
    }
}

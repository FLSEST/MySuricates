package com.automation.mapper;

import com.automation.dto.response.WishResponse;
import com.automation.entity.Project;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WishMapperTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;

    private WishMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WishMapper(projectRepository, workflowRepository);
    }

    @Test
    void toResponse_mapsAllFields() {
        Wish wish = Wish.builder()
                .id("w-1")
                .originalText("Build me a thing")
                .status(WishStatus.COMPLETED)
                .projectId(null)
                .iterationCount(3)
                .maxIterations(5)
                .resultSummary("Done")
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.id()).isEqualTo("w-1");
        assertThat(response.originalText()).isEqualTo("Build me a thing");
        assertThat(response.status()).isEqualTo(WishStatus.COMPLETED);
        assertThat(response.projectName()).isNull();
        assertThat(response.iterationCount()).isEqualTo(3);
        assertThat(response.maxIterations()).isEqualTo(5);
        assertThat(response.resultSummary()).isEqualTo("Done");
    }

    @Test
    void toResponse_resolvesProjectName() {
        Project project = new Project();
        project.setId("p-1");
        project.setName("MyProject");
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(project));

        Wish wish = Wish.builder()
                .id("w-1").originalText("test").projectId("p-1").build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.projectId()).isEqualTo("p-1");
        assertThat(response.projectName()).isEqualTo("MyProject");
    }

    @Test
    void toResponse_projectNotFound_nullProjectName() {
        when(projectRepository.findById("deleted")).thenReturn(Optional.empty());

        Wish wish = Wish.builder()
                .id("w-1").originalText("test").projectId("deleted").build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.projectName()).isNull();
    }

    @Test
    void toResponse_resolvesGeneratedWorkflowNames() {
        Workflow wf1 = Workflow.builder().id("wf-1").name("Workflow A").build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("Workflow B").build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf1));
        when(workflowRepository.findById("wf-2")).thenReturn(Optional.of(wf2));

        Wish wish = Wish.builder()
                .id("w-1").originalText("test")
                .generatedWorkflowIds(List.of("wf-1", "wf-2"))
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.generatedWorkflowNames()).containsExactly("Workflow A", "Workflow B");
    }

    @Test
    void toResponse_workflowNotFound_fallsBackToId() {
        when(workflowRepository.findById("wf-deleted")).thenReturn(Optional.empty());

        Wish wish = Wish.builder()
                .id("w-1").originalText("test")
                .generatedWorkflowIds(List.of("wf-deleted"))
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.generatedWorkflowNames()).containsExactly("wf-deleted");
    }

    @Test
    void toResponse_resolvesReferenceWorkflowNames() {
        Workflow wf = Workflow.builder().id("ref-1").name("Reference WF").build();
        when(workflowRepository.findById("ref-1")).thenReturn(Optional.of(wf));

        Wish wish = Wish.builder()
                .id("w-1").originalText("test")
                .referenceWorkflowIds(List.of("ref-1"))
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.referenceWorkflowNames()).containsExactly("Reference WF");
    }

    @Test
    void toResponse_nullWorkflowIds_emptyLists() {
        Wish wish = Wish.builder()
                .id("w-1").originalText("test")
                .generatedWorkflowIds(null)
                .referenceWorkflowIds(null)
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.generatedWorkflowNames()).isEmpty();
        assertThat(response.referenceWorkflowNames()).isEmpty();
    }

    @Test
    void toResponse_noProjectId_skipsLookup() {
        Wish wish = Wish.builder()
                .id("w-1").originalText("test").projectId(null).build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.projectName()).isNull();
        // projectRepository.findById should NOT have been called
    }

    @Test
    void toResponseList_mapsMultipleWishes() {
        Wish w1 = Wish.builder().id("w-1").originalText("First").build();
        Wish w2 = Wish.builder().id("w-2").originalText("Second").build();

        List<WishResponse> responses = mapper.toResponseList(List.of(w1, w2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).originalText()).isEqualTo("First");
        assertThat(responses.get(1).originalText()).isEqualTo("Second");
    }

    @Test
    void toResponseList_emptyList_returnsEmpty() {
        assertThat(mapper.toResponseList(List.of())).isEmpty();
    }
}

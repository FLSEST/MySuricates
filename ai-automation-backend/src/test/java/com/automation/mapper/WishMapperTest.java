package com.automation.mapper;

import com.automation.dto.response.WishResponse;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        when(projectRepository.findIdAndNameByIdIn(Set.of("p-1"))).thenReturn(rows(new Object[]{"p-1", "MyProject"}));

        Wish wish = Wish.builder()
                .id("w-1").originalText("test").projectId("p-1").build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.projectId()).isEqualTo("p-1");
        assertThat(response.projectName()).isEqualTo("MyProject");
    }

    @Test
    void toResponse_projectNotFound_nullProjectName() {
        when(projectRepository.findIdAndNameByIdIn(Set.of("deleted"))).thenReturn(List.of());

        Wish wish = Wish.builder()
                .id("w-1").originalText("test").projectId("deleted").build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.projectName()).isNull();
    }

    @Test
    void toResponse_resolvesGeneratedWorkflowNames() {
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-1", "wf-2")))
                .thenReturn(rows(new Object[]{"wf-2", "Workflow B"}, new Object[]{"wf-1", "Workflow A"}));

        Wish wish = Wish.builder()
                .id("w-1").originalText("test")
                .generatedWorkflowIds(List.of("wf-1", "wf-2"))
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.generatedWorkflowNames()).containsExactly("Workflow A", "Workflow B");
    }

    @Test
    void toResponse_workflowNotFound_fallsBackToId() {
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-deleted"))).thenReturn(List.of());

        Wish wish = Wish.builder()
                .id("w-1").originalText("test")
                .generatedWorkflowIds(List.of("wf-deleted"))
                .build();

        WishResponse response = mapper.toResponse(wish);

        assertThat(response.generatedWorkflowNames()).containsExactly("wf-deleted");
    }

    @Test
    void toResponse_resolvesReferenceWorkflowNames() {
        when(workflowRepository.findIdAndNameByIdIn(Set.of("ref-1"))).thenReturn(rows(new Object[]{"ref-1", "Reference WF"}));

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
        verifyNoInteractions(projectRepository, workflowRepository);
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

    /** GET /wishes: one query per repository for the whole list, not one per wish and id (TODO 5bf5a9c8). */
    @Test
    void toResponseList_resolvesAllNamesInOneQueryPerRepository() {
        when(projectRepository.findIdAndNameByIdIn(Set.of("p-1", "p-2")))
                .thenReturn(rows(new Object[]{"p-1", "One"}, new Object[]{"p-2", "Two"}));
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-1", "wf-2", "ref-1", "wf-gone")))
                .thenReturn(rows(new Object[]{"wf-1", "A"}, new Object[]{"wf-2", "B"}, new Object[]{"ref-1", "Ref"}));
        List<Wish> wishes = List.of(
                Wish.builder().id("w-1").originalText("x").projectId("p-1")
                        .generatedWorkflowIds(List.of("wf-1", "wf-gone")).referenceWorkflowIds(List.of("ref-1")).build(),
                Wish.builder().id("w-2").originalText("y").projectId("p-2")
                        .generatedWorkflowIds(List.of("wf-2", "wf-1")).build(),
                Wish.builder().id("w-3").originalText("z").projectId("p-1").build());

        List<WishResponse> responses = mapper.toResponseList(wishes);

        verify(projectRepository, times(1)).findIdAndNameByIdIn(anyCollection());
        verify(workflowRepository, times(1)).findIdAndNameByIdIn(anyCollection());
        verifyNoMoreInteractions(projectRepository, workflowRepository);
        assertThat(responses).extracting(WishResponse::projectName).containsExactly("One", "Two", "One");
        assertThat(responses.get(0).generatedWorkflowNames()).containsExactly("A", "wf-gone");
        assertThat(responses.get(0).referenceWorkflowNames()).containsExactly("Ref");
        assertThat(responses.get(1).generatedWorkflowNames()).containsExactly("B", "A");
        assertThat(responses.get(2).generatedWorkflowNames()).isEmpty();
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    @Test
    void toResponseList_emptyList_returnsEmpty() {
        assertThat(mapper.toResponseList(List.of())).isEmpty();
    }
}

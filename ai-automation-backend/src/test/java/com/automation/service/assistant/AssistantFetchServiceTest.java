package com.automation.service.assistant;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Wish;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantFetchServiceTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WishRepository wishRepository;
    @Mock private WorkflowPoolService workflowPoolService;
    @Mock private ProjectContextService projectContextService;
    @Mock private AssistantContextBuilder contextBuilder;

    private AssistantFetchService service;

    @BeforeEach
    void setUp() {
        service = new AssistantFetchService(workflowRunRepository, workflowRepository,
                projectRepository, wishRepository, workflowPoolService,
                projectContextService, contextBuilder);
    }

    @Test
    void parseFetchRequests_validTags_parsesList() {
        String response = "Text <fetch type=\"run_detail\" id=\"r1\"/> more <fetch type=\"wish_detail\" id=\"w1\"/>";
        List<AssistantFetchService.FetchRequest> result = service.parseFetchRequests(response);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("run_detail");
        assertThat(result.get(0).id()).isEqualTo("r1");
        assertThat(result.get(1).type()).isEqualTo("wish_detail");
    }

    @Test
    void parseFetchRequests_noTags_emptyList() {
        assertThat(service.parseFetchRequests("plain text")).isEmpty();
    }

    @Test
    void parseFetchRequests_null_emptyList() {
        assertThat(service.parseFetchRequests(null)).isEmpty();
    }

    @Test
    void stripFetchTags_removesTags() {
        String input = "Before <fetch type=\"run_detail\" id=\"r1\"/> After";
        String result = service.stripFetchTags(input);
        assertThat(result).isEqualTo("Before  After");
    }

    @Test
    void stripFetchTags_null_returnsNull() {
        assertThat(service.stripFetchTags(null)).isNull();
    }

    @Test
    void executeFetch_runDetail_formatsRun() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(5000L).steps(new ArrayList<>()).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        when(contextBuilder.runLabel(run)).thenReturn("TestWorkflow (TestProject)");
        when(contextBuilder.extractError(run)).thenReturn(null);

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "r1"));

        assertThat(result).contains("r1");
        assertThat(result).contains("COMPLETED");
    }

    @Test
    void executeFetch_runDetail_notFound_returnsNotFoundMessage() {
        when(workflowRunRepository.findByIdWithSteps("missing")).thenReturn(Optional.empty());

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("run_detail", "missing"));

        assertThat(result).contains("nicht gefunden");
    }

    @Test
    void executeFetch_stepOutput_validIndex_returnsOutput() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output("step output text").build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(workflowRunRepository.findByIdWithSteps("r1")).thenReturn(Optional.of(run));
        // step_output uses 1-based indexing
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("step_output", "r1/1"));

        assertThat(result).contains("step output text");
    }

    @Test
    void executeFetch_unknownType_returnsEmpty() {
        String result = service.executeFetch(new AssistantFetchService.FetchRequest("nonexistent", "id"));
        assertThat(result).isEmpty();
    }

    @Test
    void executeFetch_wishDetail_formatsWish() {
        Wish wish = Wish.builder().id("w1").originalText("Build me a thing").build();
        when(wishRepository.findById("w1")).thenReturn(Optional.of(wish));
        when(contextBuilder.truncate("Build me a thing", 200)).thenReturn("Build me a thing");

        String result = service.executeFetch(new AssistantFetchService.FetchRequest("wish_detail", "w1"));

        assertThat(result).contains("Build me a thing");
    }
}

package com.automation.mapper;

import com.automation.dto.response.ChainResponse;
import com.automation.dto.response.ChainRunResponse;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainMapperTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private ChainRunRepository chainRunRepository;

    private ChainMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ChainMapper(workflowRepository, chainRunRepository);
    }

    @Nested
    class ToResponse {

        @Test
        void mapsBasicFields() {
            WorkflowChain chain = WorkflowChain.builder()
                    .id("ch-1").name("Test Chain").description("desc").icon("⛓️")
                    .entries(List.of()).stopOnFailure(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build();
            when(chainRunRepository.countByChainId("ch-1")).thenReturn(5L);

            ChainResponse response = mapper.toResponse(chain);

            assertThat(response.id()).isEqualTo("ch-1");
            assertThat(response.name()).isEqualTo("Test Chain");
            assertThat(response.runCount()).isEqualTo(5);
            assertThat(response.stopOnFailure()).isTrue();
        }

        @Test
        void resolvesEntryWorkflowNames() {
            Project project = new Project();
            project.setName("MyProject");
            Workflow wf = Workflow.builder().id("wf-1").name("WF A").project(project).build();
            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
            when(chainRunRepository.countByChainId("ch-1")).thenReturn(0L);

            ChainEntry entry = new ChainEntry();
            entry.setWorkflowId("wf-1");
            entry.setLabel("Step 1");
            entry.setSortOrder(0);

            WorkflowChain chain = WorkflowChain.builder()
                    .id("ch-1").name("Chain").entries(List.of(entry)).build();

            ChainResponse response = mapper.toResponse(chain);

            assertThat(response.entries()).hasSize(1);
            assertThat(response.entries().get(0).workflowName()).isEqualTo("WF A");
            assertThat(response.entries().get(0).projectName()).isEqualTo("MyProject");
        }

        @Test
        void workflowNotFound_nullNames() {
            when(workflowRepository.findById("wf-deleted")).thenReturn(Optional.empty());
            when(chainRunRepository.countByChainId("ch-1")).thenReturn(0L);

            ChainEntry entry = new ChainEntry();
            entry.setWorkflowId("wf-deleted");

            WorkflowChain chain = WorkflowChain.builder()
                    .id("ch-1").name("Chain").entries(List.of(entry)).build();

            ChainResponse response = mapper.toResponse(chain);

            assertThat(response.entries().get(0).workflowName()).isNull();
            assertThat(response.entries().get(0).projectName()).isNull();
        }
    }

    @Nested
    class ToRunResponse {

        @Test
        void mapsChainRunFields() {
            WorkflowChain chain = WorkflowChain.builder().id("ch-1").name("Chain A").build();

            ChainRunEntry runEntry = new ChainRunEntry();
            runEntry.setEntryIndex(0);
            runEntry.setWorkflowId("wf-1");
            runEntry.setWorkflowName("WF A");
            runEntry.setStatus(RunStatus.COMPLETED);

            ChainRun run = ChainRun.builder()
                    .id("cr-1").chain(chain).status(RunStatus.COMPLETED)
                    .entryRuns(List.of(runEntry))
                    .startedAt(Instant.now()).build();

            ChainRunResponse response = mapper.toRunResponse(run);

            assertThat(response.id()).isEqualTo("cr-1");
            assertThat(response.chainName()).isEqualTo("Chain A");
            assertThat(response.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(response.entryRuns()).hasSize(1);
            assertThat(response.entryRuns().get(0).workflowName()).isEqualTo("WF A");
        }

        @Test
        void nullChain_handlesGracefully() {
            ChainRun run = ChainRun.builder()
                    .id("cr-1").chain(null).status(RunStatus.FAILED)
                    .entryRuns(List.of()).build();

            ChainRunResponse response = mapper.toRunResponse(run);

            assertThat(response.chainId()).isNull();
            assertThat(response.chainName()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        WorkflowChain c1 = WorkflowChain.builder().id("c1").name("A").entries(List.of()).build();
        WorkflowChain c2 = WorkflowChain.builder().id("c2").name("B").entries(List.of()).build();

        List<ChainResponse> responses = mapper.toResponseList(List.of(c1, c2));

        assertThat(responses).hasSize(2);
    }

    @Test
    void toResponseList_usesGroupedRunCounts() {
        WorkflowChain c1 = WorkflowChain.builder().id("c1").name("A").entries(List.of()).build();
        WorkflowChain c2 = WorkflowChain.builder().id("c2").name("B").entries(List.of()).build();
        // Batch path: one grouped query instead of countByChainId per chain.
        when(chainRunRepository.countRunsGroupedByChain()).thenReturn(List.of(
                new Object[]{"c1", 3L}, new Object[]{"c2", 7L}));

        List<ChainResponse> responses = mapper.toResponseList(List.of(c1, c2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).runCount()).isEqualTo(3L);
        assertThat(responses.get(1).runCount()).isEqualTo(7L);
    }

    @Test
    void toResponseList_batchResolvesEntryWorkflowNames() {
        Project project = new Project();
        project.setName("MyProject");
        Workflow wf = Workflow.builder().id("wf-1").name("WF A").project(project).build();
        // Batch path: one findAllById instead of findById per entry.
        when(workflowRepository.findAllById(anySet())).thenReturn(List.of(wf));

        ChainEntry entry = new ChainEntry();
        entry.setWorkflowId("wf-1");
        entry.setSortOrder(0);
        WorkflowChain chain = WorkflowChain.builder()
                .id("ch-1").name("Chain").entries(List.of(entry)).build();

        List<ChainResponse> responses = mapper.toResponseList(List.of(chain));

        assertThat(responses.get(0).entries().get(0).workflowName()).isEqualTo("WF A");
        assertThat(responses.get(0).entries().get(0).projectName()).isEqualTo("MyProject");
    }
}

package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowVersionServiceTest {

    @Mock private WorkflowVersionRepository versionRepository;

    private WorkflowVersionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new WorkflowVersionService(versionRepository, objectMapper);
    }

    @Nested
    class SnapshotBeforeUpdate {

        @Test
        void noChanges_returnsEmpty() {
            Workflow workflow = Workflow.builder().id("wf-1")
                    .blocks(List.of()).instructions("do stuff").model("claude-sonnet-4-6")
                    .build();
            WorkflowSnapshot incoming = WorkflowSnapshot.from(workflow);

            Optional<Integer> result = service.snapshotBeforeUpdate(workflow, incoming, "user", "test");

            assertThat(result).isEmpty();
            verify(versionRepository, never()).save(any());
        }

        @Test
        void withChanges_savesSnapshotAndReturnsVersion() {
            Workflow workflow = Workflow.builder().id("wf-1")
                    .blocks(List.of()).instructions("old instructions").model("claude-sonnet-4-6")
                    .build();
            WorkflowSnapshot incoming = new WorkflowSnapshot(
                    List.of(), "new instructions", "claude-sonnet-4-6", null, Map.of(), Map.of(), List.of());

            when(versionRepository.countByWorkflowId("wf-1")).thenReturn(3);

            Optional<Integer> result = service.snapshotBeforeUpdate(workflow, incoming, "user", "Updated instructions");

            assertThat(result).contains(4); // 3 + 1

            ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
            verify(versionRepository).save(captor.capture());
            WorkflowVersion saved = captor.getValue();
            assertThat(saved.getWorkflowId()).isEqualTo("wf-1");
            assertThat(saved.getVersionNumber()).isEqualTo(4);
            assertThat(saved.getChangeSource()).isEqualTo("user");
            assertThat(saved.getChangeMessage()).isEqualTo("Updated instructions");
            assertThat(saved.getSnapshot()).contains("old instructions");
        }

        @Test
        void firstVersion_returnsOne() {
            Workflow workflow = Workflow.builder().id("wf-1")
                    .blocks(List.of()).instructions("old").model("m1")
                    .build();
            WorkflowSnapshot incoming = new WorkflowSnapshot(
                    List.of(), "new", "m1", null, Map.of(), Map.of(), List.of());

            when(versionRepository.countByWorkflowId("wf-1")).thenReturn(0);

            Optional<Integer> result = service.snapshotBeforeUpdate(workflow, incoming, "api", null);

            assertThat(result).contains(1);
        }

        @Test
        void modelChange_detected() {
            Workflow workflow = Workflow.builder().id("wf-1")
                    .blocks(List.of()).model("claude-sonnet-4-6")
                    .build();
            WorkflowSnapshot incoming = new WorkflowSnapshot(
                    List.of(), null, "claude-opus-4-6", null, Map.of(), Map.of(), List.of());

            when(versionRepository.countByWorkflowId("wf-1")).thenReturn(0);

            Optional<Integer> result = service.snapshotBeforeUpdate(workflow, incoming, "user", null);

            assertThat(result).isPresent();
            verify(versionRepository).save(any());
        }

        @Test
        void blocksChange_detected() {
            BlockDefinition block = new BlockDefinition();
            block.setType("simple");
            Workflow workflow = Workflow.builder().id("wf-1")
                    .blocks(List.of(block))
                    .build();
            WorkflowSnapshot incoming = new WorkflowSnapshot(
                    List.of(), null, null, null, Map.of(), Map.of(), List.of());

            when(versionRepository.countByWorkflowId("wf-1")).thenReturn(0);

            Optional<Integer> result = service.snapshotBeforeUpdate(workflow, incoming, "user", null);

            assertThat(result).isPresent();
        }
    }

    @Nested
    class FindMethods {

        @Test
        void findByWorkflowId_returnsPaginatedResults() {
            WorkflowVersion v1 = WorkflowVersion.builder().workflowId("wf-1").versionNumber(1).build();
            Page<WorkflowVersion> page = new PageImpl<>(List.of(v1));
            when(versionRepository.findByWorkflowIdOrderByVersionNumberDesc("wf-1", PageRequest.of(0, 10)))
                    .thenReturn(page);

            Page<WorkflowVersion> result = service.findByWorkflowId("wf-1", PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        void findById_found_returnsVersion() {
            WorkflowVersion version = WorkflowVersion.builder().id("v-1").workflowId("wf-1").build();
            when(versionRepository.findById("v-1")).thenReturn(Optional.of(version));

            WorkflowVersion result = service.findById("v-1");

            assertThat(result.getId()).isEqualTo("v-1");
        }

        @Test
        void findById_notFound_throwsResourceNotFoundException() {
            when(versionRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("missing"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("WorkflowVersion");
        }

        @Test
        void findLatest_returnsHighestVersion() {
            WorkflowVersion v3 = WorkflowVersion.builder().workflowId("wf-1").versionNumber(3).build();
            when(versionRepository.findTopByWorkflowIdOrderByVersionNumberDesc("wf-1"))
                    .thenReturn(Optional.of(v3));

            Optional<WorkflowVersion> result = service.findLatest("wf-1");

            assertThat(result).isPresent();
            assertThat(result.get().getVersionNumber()).isEqualTo(3);
        }

        @Test
        void findLatest_noVersions_returnsEmpty() {
            when(versionRepository.findTopByWorkflowIdOrderByVersionNumberDesc("wf-1"))
                    .thenReturn(Optional.empty());

            assertThat(service.findLatest("wf-1")).isEmpty();
        }

        @Test
        void getVersionCount_returnsCount() {
            when(versionRepository.countByWorkflowId("wf-1")).thenReturn(5);

            assertThat(service.getVersionCount("wf-1")).isEqualTo(5);
        }

        @Test
        void getVersionCount_noVersions_returnsZero() {
            when(versionRepository.countByWorkflowId("wf-1")).thenReturn(0);

            assertThat(service.getVersionCount("wf-1")).isZero();
        }
    }
}

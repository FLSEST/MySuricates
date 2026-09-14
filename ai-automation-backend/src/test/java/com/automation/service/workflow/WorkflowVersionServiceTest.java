package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.ChangeSource;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    class SaveVersion {

        private final WorkflowSnapshot snapshot = new WorkflowSnapshot(
                List.of(), "changed instructions", "claude-sonnet-4-6", "CLAUDE", Map.of("k", "v"), Map.of(), List.of());

        @Test
        void numbersAfterTheHighestExistingVersion() {
            when(versionRepository.maxVersionNumber("wf-1")).thenReturn(7);

            int number = service.saveVersion("wf-1", snapshot, "hash-1", ChangeSource.ASSISTANT, "via tool");

            ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
            verify(versionRepository).save(captor.capture());
            WorkflowVersion saved = captor.getValue();
            assertThat(number).isEqualTo(8);
            assertThat(saved.getVersionNumber()).isEqualTo(8);
            assertThat(saved.getChangeSource()).isEqualTo("assistant");
            assertThat(saved.getChangeMessage()).isEqualTo("via tool");
            assertThat(saved.getDefinitionHash()).isEqualTo("hash-1");
        }

        @Test
        void firstVersionIsOne() {
            when(versionRepository.maxVersionNumber("wf-1")).thenReturn(0);

            assertThat(service.saveVersion("wf-1", snapshot, "h", ChangeSource.USER, null)).isEqualTo(1);
        }

        @Test
        void savedSnapshotRoundTrips() throws Exception {
            when(versionRepository.maxVersionNumber("wf-1")).thenReturn(0);

            service.saveVersion("wf-1", snapshot, "h", ChangeSource.USER, null);

            ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
            verify(versionRepository).save(captor.capture());
            WorkflowSnapshot restored = objectMapper.readValue(captor.getValue().getSnapshot(), WorkflowSnapshot.class);
            assertThat(restored.differs(snapshot)).isFalse();
        }

        @Test
        void unserialisableSnapshotFailsLoudInsteadOfLeavingAGap() throws Exception {
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
            WorkflowVersionService failingService = new WorkflowVersionService(versionRepository, failingMapper);

            assertThatThrownBy(() -> failingService.saveVersion("wf-1", snapshot, "h", ChangeSource.USER, null))
                    .isInstanceOf(IllegalStateException.class);
            verify(versionRepository, never()).save(any());
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

        @Test
        void findByWorkflowId_noVersions_returnsEmptyPage() {
            when(versionRepository.findByWorkflowIdOrderByVersionNumberDesc("wf-1", PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(List.of()));

            Page<WorkflowVersion> result = service.findByWorkflowId("wf-1", PageRequest.of(0, 10));

            assertThat(result.getContent()).isEmpty();
        }
    }
}

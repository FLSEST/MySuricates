package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowVersionService {

    private final WorkflowVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Creates a snapshot of the workflow's current state BEFORE an update.
     * Must be called within the same @Transactional as the update.
     *
     * @return the new version number, or empty if nothing changed
     */
    public Optional<Integer> snapshotBeforeUpdate(Workflow workflow, WorkflowSnapshot incomingState,
                                                   String changeSource, String changeMessage) {
        WorkflowSnapshot currentState = WorkflowSnapshot.from(workflow);

        if (!currentState.differs(incomingState)) {
            log.debug("No versioned fields changed for workflow {}, skipping snapshot", workflow.getId());
            return Optional.empty();
        }

        int nextVersion = versionRepository.countByWorkflowId(workflow.getId()) + 1;

        saveSnapshot(workflow.getId(), nextVersion, currentState, changeSource, changeMessage);
        return Optional.of(nextVersion);
    }

    public Page<WorkflowVersion> findByWorkflowId(String workflowId, Pageable pageable) {
        return versionRepository.findByWorkflowIdOrderByVersionNumberDesc(workflowId, pageable);
    }

    public WorkflowVersion findById(String versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowVersion", versionId));
    }

    public Optional<WorkflowVersion> findLatest(String workflowId) {
        return versionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId);
    }

    public int getVersionCount(String workflowId) {
        return versionRepository.countByWorkflowId(workflowId);
    }

    private void saveSnapshot(String workflowId, int versionNumber,
                              WorkflowSnapshot snapshot, String changeSource, String changeMessage) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            WorkflowVersion version = WorkflowVersion.builder()
                    .workflowId(workflowId)
                    .versionNumber(versionNumber)
                    .snapshot(json)
                    .changeSource(changeSource)
                    .changeMessage(changeMessage)
                    .build();
            versionRepository.save(version);
            log.info("Saved workflow version {} for workflow {} (source: {})",
                    versionNumber, workflowId, changeSource);
        } catch (Exception e) {
            log.error("Failed to serialize workflow snapshot for {}", workflowId, e);
        }
    }
}

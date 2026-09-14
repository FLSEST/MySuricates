package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.ChangeSource;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
     * Writes a version row for {@code snapshot}. Numbering is {@code MAX(version_number) + 1}, so gaps
     * never produce duplicates. Must run inside the transaction of the change it records.
     *
     * @return the new version number
     */
    public int saveVersion(String workflowId, WorkflowSnapshot snapshot, String definitionHash,
                           ChangeSource source, String changeMessage) {
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Workflow snapshot of " + workflowId + " is not serialisable", e);
        }
        int versionNumber = versionRepository.maxVersionNumber(workflowId) + 1;
        versionRepository.save(WorkflowVersion.builder()
                .workflowId(workflowId)
                .versionNumber(versionNumber)
                .snapshot(json)
                .definitionHash(definitionHash)
                .changeSource(source.key())
                .changeMessage(changeMessage)
                .build());
        log.info("Saved workflow version {} for workflow {} (source: {})", versionNumber, workflowId, source.key());
        return versionNumber;
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

    /** Newest version row whose snapshot has exactly this definition hash. */
    public Optional<String> findVersionIdByHash(String workflowId, String definitionHash) {
        return versionRepository.findTopByWorkflowIdAndDefinitionHashOrderByVersionNumberDesc(workflowId, definitionHash)
                .map(WorkflowVersion::getId);
    }
}

package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.entity.WorkflowSealEvent;
import com.automation.entity.WorkflowSealEvent.Action;
import com.automation.repository.WorkflowSealEventRepository;
import com.automation.service.skill.SkillContentHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Writes and checks the seal history. Every entry carries the hash of the previous one across all
 * workflows, so removing or editing a row later breaks the chain — the history says what happened
 * even after the activity log has been pruned and after the workflow itself is gone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSealHistory {

    private final WorkflowSealEventRepository repository;

    @Transactional
    public WorkflowSealEvent record(Workflow workflow, Action action, String definitionHash,
                                    Map<String, String> skillPins, String signature) {
        String prev = repository.findTopByOrderByOccurredAtDescIdDesc()
                .map(WorkflowSealEvent::getEventHash).orElse(null);
        WorkflowSealEvent event = WorkflowSealEvent.builder()
                .workflowId(workflow.getId())
                .workflowName(workflow.getName())
                .action(action)
                .occurredAt(Instant.now())
                .definitionHash(definitionHash)
                .skillPins(skillPins == null ? null : WorkflowSkillPins.toJson(skillPins))
                .signature(signature)
                .prevEventHash(prev)
                .build();
        event.setEventHash(hashOf(event));
        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<WorkflowSealEvent> forWorkflow(String workflowId) {
        return repository.findByWorkflowIdOrderByOccurredAtDesc(workflowId);
    }

    /**
     * Re-walks the whole chain. Returns the entries whose own hash or predecessor no longer fits —
     * empty means the history is intact as stored.
     */
    @Transactional(readOnly = true)
    public List<String> brokenChainEntries() {
        String expectedPrev = null;
        List<String> broken = new java.util.ArrayList<>();
        for (WorkflowSealEvent event : repository.findAllByOrderByOccurredAtAscIdAsc()) {
            if (!hashOf(event).equals(event.getEventHash())
                    || !java.util.Objects.equals(expectedPrev, event.getPrevEventHash())) {
                broken.add(event.getId());
            }
            expectedPrev = event.getEventHash();
        }
        return broken;
    }

    /** Hash over the entry's content and its predecessor — never over its own {@code eventHash}. */
    static String hashOf(WorkflowSealEvent e) {
        String text = String.join("|", "v1",
                String.valueOf(e.getWorkflowId()),
                String.valueOf(e.getAction()),
                String.valueOf(e.getOccurredAt()),
                String.valueOf(e.getDefinitionHash()),
                String.valueOf(e.getSkillPins()),
                String.valueOf(e.getSignature()),
                String.valueOf(e.getPrevEventHash()));
        return SkillContentHasher.sha256(text);
    }
}

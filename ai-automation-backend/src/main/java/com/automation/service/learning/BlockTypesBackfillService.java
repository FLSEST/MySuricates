package com.automation.service.learning;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Backfills the denormalized block_types column for existing workflows on startup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlockTypesBackfillService {

    private final WorkflowRepository workflowRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillBlockTypes() {
        List<Workflow> workflows = workflowRepository.findWithEmptyBlockTypes();
        if (workflows.isEmpty()) {
            return;
        }

        int count = 0;
        for (Workflow workflow : workflows) {
            String computed = computeBlockTypes(workflow.getBlocks());
            if (!computed.isEmpty()) {
                workflow.setBlockTypes(computed);
                workflowRepository.save(workflow);
                count++;
            }
        }

        if (count > 0) {
            log.info("Backfilled block_types for {} workflows", count);
        }
    }

    /**
     * Computes a sorted, comma-separated string of unique block types.
     */
    public static String computeBlockTypes(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
                .map(BlockDefinition::getType)
                .filter(t -> t != null && !t.isEmpty())
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}

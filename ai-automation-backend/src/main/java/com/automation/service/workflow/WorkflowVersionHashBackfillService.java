package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.WorkflowVersion;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * One-shot on startup: hashes version rows written before definition hashes existed. Pure CPU in
 * short chunked transactions (pool-size 1). Unparseable snapshots stay without hash and are logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowVersionHashBackfillService {

    static final int CHUNK = 100;

    private final WorkflowVersionRepository versionRepository;
    private final WorkflowDefinitionHasher hasher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ModuleRegistry modules;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        if (!modules.isEnabled(ModuleCatalog.PROVENANCE)) {
            return;
        }
        List<String> ids = versionRepository.findIdsWithoutDefinitionHash();
        if (ids.isEmpty()) {
            return;
        }
        int hashed = 0;
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
            Integer done = transactionTemplate.execute(status -> hashChunk(chunk));
            hashed += done != null ? done : 0;
        }
        log.info("Backfilled definition hashes for {}/{} workflow versions", hashed, ids.size());
    }

    private int hashChunk(List<String> ids) {
        int hashed = 0;
        for (WorkflowVersion version : versionRepository.findAllById(ids)) {
            try {
                WorkflowSnapshot snapshot = objectMapper.readValue(version.getSnapshot(), WorkflowSnapshot.class);
                version.setDefinitionHash(hasher.hash(snapshot));
                hashed++;
            } catch (Exception e) {
                log.warn("Version {} of workflow {} has an unreadable snapshot, left without hash: {}",
                        version.getId(), version.getWorkflowId(), e.getMessage());
            }
        }
        return hashed;
    }
}

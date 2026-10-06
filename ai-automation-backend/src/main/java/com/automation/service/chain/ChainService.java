package com.automation.service.chain;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.UpdateChainRequest;
import com.automation.entity.ChainEntry;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowChain;
import com.automation.exception.ElementNotFoundException;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedStateException;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChainService {

    private final WorkflowChainRepository chainRepository;
    private final WorkflowRepository workflowRepository;
    private final ChainRunRepository chainRunRepository;

    public List<WorkflowChain> findAll() {
        return chainRepository.findAll();
    }

    public WorkflowChain findById(String id) {
        return chainRepository.findById(id)
                .orElseThrow(() -> new ElementNotFoundException("Chain", id));
    }

    public WorkflowChain create(CreateChainRequest request) {
        validateWorkflowIds(request.entries());

        WorkflowChain chain = WorkflowChain.builder()
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .entries(toEntries(request.entries()))
                .stopOnFailure(request.stopOnFailure() != null ? request.stopOnFailure() : true)
                .build();

        return chainRepository.save(chain);
    }

    public WorkflowChain update(String id, UpdateChainRequest request) {
        WorkflowChain chain = findById(id);

        if (request.name() != null) {
            chain.setName(request.name());
        }
        if (request.description() != null) {
            chain.setDescription(request.description());
        }
        if (request.icon() != null) {
            chain.setIcon(request.icon());
        }
        if (request.entries() != null) {
            validateWorkflowIds(request.entries());
            chain.setEntries(toEntries(request.entries()));
        }
        if (request.stopOnFailure() != null) {
            chain.setStopOnFailure(request.stopOnFailure());
        }

        return chainRepository.save(chain);
    }

    /** Run states in which a chain run still starts workflows. */
    private static final Set<RunStatus> ACTIVE_RUN_STATUSES =
            EnumSet.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.PAUSED);

    /**
     * Refused (409) while the chain has a pending, running or paused run: the executor would keep
     * starting its workflows — and their project containers — after the chain is gone.
     * Best effort, without a lock: a run starting at the same moment can slip through.
     */
    public void delete(String id) {
        WorkflowChain chain = findById(id);
        if (chainRunRepository.existsByChainIdAndStatusIn(id, ACTIVE_RUN_STATUSES)) {
            throw new LocalizedStateException(ErrorMessage.CHAIN_RUN_ACTIVE);
        }
        chainRepository.delete(chain);
    }

    // --- Chain runs (repository access stays in the service layer) ---

    public Page<ChainRun> findRunsFiltered(RunStatus status, String search, Pageable pageable) {
        return chainRunRepository.findFiltered(status, search, pageable);
    }

    public List<ChainRun> findRunsByChainId(String chainId) {
        findById(chainId); // verify chain exists
        return chainRunRepository.findByChainIdOrderByStartedAtDesc(chainId);
    }

    public ChainRun findRunById(String runId) {
        return chainRunRepository.findById(runId)
                .orElseThrow(() -> new ElementNotFoundException("Chain run", runId));
    }

    /**
     * Check all workflow IDs of the entries in one query instead of {@code existsById} per entry
     * (TODO 6299a860); the first missing ID in entry order is reported as before.
     */
    private void validateWorkflowIds(List<ChainEntryRequest> entries) {
        if (entries.isEmpty()) return;
        Set<String> ids = entries.stream().map(ChainEntryRequest::workflowId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> existing = ids.isEmpty() ? Set.of() : new HashSet<>(workflowRepository.findExistingIdsIn(ids));
        entries.stream().map(ChainEntryRequest::workflowId)
                .filter(id -> !existing.contains(id))
                .findFirst()
                .ifPresent(missing -> {
                    throw new IllegalArgumentException("Workflow not found: " + missing);
                });
    }

    private List<ChainEntry> toEntries(List<ChainEntryRequest> requests) {
        return requests.stream()
                .map(r -> ChainEntry.builder()
                        .workflowId(r.workflowId())
                        .label(r.label())
                        .sortOrder(r.sortOrder())
                        .stopOnFailure(r.stopOnFailure() != null ? r.stopOnFailure() : true)
                        .inputContext(r.inputContext() != null ? r.inputContext() : java.util.Map.of())
                        .timeoutMinutes(entryTimeout(r.timeoutMinutes()))
                        .build())
                .toList();
    }

    static final int MIN_ENTRY_TIMEOUT_MINUTES = 5;
    static final int MAX_ENTRY_TIMEOUT_MINUTES = 1440;

    /** Wait time per entry (TODO cc0eb3ba): null = global default, otherwise 5–1440 minutes. */
    private static Integer entryTimeout(Integer minutes) {
        if (minutes == null) return null;
        if (minutes < MIN_ENTRY_TIMEOUT_MINUTES || minutes > MAX_ENTRY_TIMEOUT_MINUTES) {
            throw new IllegalArgumentException("timeoutMinutes muss zwischen 5 und 1440 liegen: " + minutes);
        }
        return minutes;
    }
}

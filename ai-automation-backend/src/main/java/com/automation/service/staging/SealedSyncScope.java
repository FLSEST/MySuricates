package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncPreviewItem;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChainEntry;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.executor.NestedBlocks;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowSealVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * What may go to Prod (TODO 84a78bb7): only workflows whose seal holds — the same check
 * {@code RunAdmission} runs —, their projects, the custom skills they use and chains made only of
 * such workflows. A selection from the UI is narrowed to that; nothing outside it is ever sent.
 *
 * <p>What stays behind although something sent depends on it is named in notes: a sealed workflow
 * calling an unsealed sub-workflow, a chain with an unsealed workflow.
 */
@Component
@RequiredArgsConstructor
public class SealedSyncScope {

    static final String SUB_WORKFLOW = "sub-workflow";
    /** Current key and the legacy key the sub-workflow executor still reads. */
    private static final List<String> WORKFLOW_ID_KEYS = List.of("workflow_id", "workflowId");

    /**
     * Ids allowed for a sealed-only target, and what stays behind: per sealed workflow the notes about its
     * unsealed sub-workflows, per left-out chain why.
     */
    public record Scope(Set<String> workflowIds, Set<String> projectIds, Set<String> skillIds, Set<String> chainIds,
                        Map<String, List<String>> workflowNotes, Map<String, String> chainNotes) {

        /** All notes, for the preview. */
        public List<String> notes() {
            List<String> notes = new ArrayList<>();
            workflowNotes.values().forEach(notes::addAll);
            notes.addAll(chainNotes.values());
            return notes;
        }

        /**
         * The notes that concern a sync: those of the workflows sent, and of the left-out chains that were
         * asked for ({@code null} = all).
         */
        public List<String> notesFor(Collection<String> sentWorkflowIds, Collection<String> requestedChainIds) {
            List<String> notes = new ArrayList<>();
            workflowNotes.forEach((id, list) -> {
                if (sentWorkflowIds.contains(id)) {
                    notes.addAll(list);
                }
            });
            chainNotes.forEach((id, note) -> {
                if (requestedChainIds == null || requestedChainIds.contains(id)) {
                    notes.add(note);
                }
            });
            return notes;
        }
    }

    private final WorkflowRepository workflowRepository;
    private final WorkflowChainRepository chainRepository;
    private final WorkflowSealVerifier verifier;
    private final PlatformTransactionManager transactionManager;

    /** One short read-only transaction (skills are loaded lazily); no remote call inside. */
    public Scope scope() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> computeScope());
    }

    private Scope computeScope() {
        Set<String> workflowIds = new HashSet<>();
        Set<String> projectIds = new HashSet<>();
        Set<String> skillIds = new HashSet<>();
        Map<String, Set<String>> subWorkflows = new LinkedHashMap<>();
        Map<String, String> sealedNames = new HashMap<>();
        for (Workflow workflow : workflowRepository.findSealedWithSkills()) {
            WorkflowSealVerifier.Verdict verdict = verifier.verify(workflow);
            if (!verdict.intact()) {
                continue;
            }
            workflowIds.add(workflow.getId());
            sealedNames.put(workflow.getId(), workflow.getName());
            if (workflow.getProject() != null) {
                projectIds.add(workflow.getProject().getId());
            }
            skillIds.addAll(verdict.pins() != null ? verdict.pins().keySet() : usedSkillIds(workflow));
            subWorkflows.put(workflow.getId(), subWorkflowIds(workflow));
        }

        Set<String> unsealedRefs = new TreeSet<>();
        subWorkflows.values().forEach(ids -> ids.stream().filter(id -> !workflowIds.contains(id)).forEach(unsealedRefs::add));
        List<WorkflowChain> chains = chainRepository.findAll();
        for (WorkflowChain chain : chains) {
            if (chain.getEntries() != null) {
                chain.getEntries().stream().map(ChainEntry::getWorkflowId).filter(Objects::nonNull)
                        .filter(id -> !workflowIds.contains(id)).forEach(unsealedRefs::add);
            }
        }
        Map<String, String> names = names(unsealedRefs);

        Map<String, List<String>> workflowNotes = new LinkedHashMap<>();
        subWorkflows.forEach((id, refs) -> {
            List<String> notes = refs.stream().filter(ref -> !workflowIds.contains(ref))
                    .map(ref -> "Workflow '" + sealedNames.get(id) + "' calls sub-workflow '" + names.getOrDefault(ref, ref)
                            + "', which is not sealed — it stays in Dev and must already exist on Prod")
                    .toList();
            if (!notes.isEmpty()) {
                workflowNotes.put(id, notes);
            }
        });

        Set<String> chainIds = new HashSet<>();
        Map<String, String> chainNotes = new LinkedHashMap<>();
        for (WorkflowChain chain : chains) {
            List<ChainEntry> entries = chain.getEntries();
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            List<String> unsealed = entries.stream().map(ChainEntry::getWorkflowId)
                    .filter(id -> !workflowIds.contains(id)).distinct()
                    .map(id -> "'" + names.getOrDefault(id, String.valueOf(id)) + "'").toList();
            if (unsealed.isEmpty()) {
                chainIds.add(chain.getId());
            } else {
                chainNotes.put(chain.getId(), "Chain '" + chain.getName() + "' stays in Dev: "
                        + String.join(", ", unsealed) + (unsealed.size() == 1 ? " is" : " are") + " not sealed");
            }
        }
        return new Scope(workflowIds, projectIds, skillIds, chainIds, workflowNotes, chainNotes);
    }

    private Map<String, String> names(Collection<String> ids) {
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : workflowRepository.findIdAndNameByIdIn(ids)) {
                names.put((String) row[0], (String) row[1]);
            }
        }
        return names;
    }

    /** Narrows a selection (or "everything" = {@code null}) to the scope. */
    public SyncDefinitionsRequest restrict(SyncDefinitionsRequest request) {
        return restrict(request, scope());
    }

    public static SyncDefinitionsRequest restrict(SyncDefinitionsRequest request, Scope scope) {
        return new SyncDefinitionsRequest(
                intersect(request == null ? null : request.projectIds(), scope.projectIds()),
                intersect(request == null ? null : request.workflowIds(), scope.workflowIds()),
                intersect(request == null ? null : request.skillIds(), scope.skillIds()),
                intersect(request == null ? null : request.chainIds(), scope.chainIds()));
    }

    /** Leaves only the items in the scope and names what stays behind, so the preview shows what a sync would send. */
    public SyncPreview restrict(SyncPreview preview) {
        Scope scope = scope();
        return new SyncPreview(
                only(preview.projects(), scope.projectIds()),
                only(preview.workflows(), scope.workflowIds()),
                only(preview.skills(), scope.skillIds()),
                only(preview.chains(), scope.chainIds()),
                scope.notes());
    }

    private static List<String> intersect(Collection<String> selected, Set<String> allowed) {
        if (selected == null) {
            return allowed.stream().sorted().toList();
        }
        return selected.stream().filter(allowed::contains).distinct().toList();
    }

    private static List<SyncPreviewItem> only(List<SyncPreviewItem> items, Set<String> allowed) {
        return items == null ? List.of() : items.stream().filter(i -> allowed.contains(i.id())).toList();
    }

    /** Workflow ids the definition calls as sub-workflows, nested blocks included. */
    static Set<String> subWorkflowIds(Workflow workflow) {
        Set<String> ids = new TreeSet<>();
        Consumer<Map<String, Object>> collect = config -> {
            if (config == null) {
                return;
            }
            for (String key : WORKFLOW_ID_KEYS) {
                if (config.get(key) instanceof String id && !id.isBlank()) {
                    ids.add(id);
                }
            }
        };
        if (workflow.getBlocks() != null) {
            for (BlockDefinition block : workflow.getBlocks()) {
                if (SUB_WORKFLOW.equals(block.getType())) {
                    collect.accept(block.getConfig());
                }
                NestedBlocks.visit(block.getConfig(), nested -> {
                    if (SUB_WORKFLOW.equals(nested.type())) {
                        collect.accept(nested.config());
                    }
                });
            }
        }
        return ids;
    }

    /** For seals from before skills were pinned: the skills the definition names itself. */
    private static Set<String> usedSkillIds(Workflow workflow) {
        Set<String> ids = new HashSet<>();
        if (workflow.getActivatedSkills() != null) {
            workflow.getActivatedSkills().stream().map(Skill::getId).filter(Objects::nonNull).forEach(ids::add);
        }
        if (workflow.getBlocks() != null) {
            for (BlockDefinition block : workflow.getBlocks()) {
                if (block.getSkillIds() != null) {
                    block.getSkillIds().stream().filter(Objects::nonNull).forEach(ids::add);
                }
            }
        }
        return ids;
    }
}

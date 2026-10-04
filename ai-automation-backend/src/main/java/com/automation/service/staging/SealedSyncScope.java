package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncPreviewItem;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChainEntry;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowSealVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What may go to Prod (TODO 84a78bb7): only workflows whose seal holds — the same check
 * {@code RunAdmission} runs —, their projects, the custom skills they use and chains made only of
 * such workflows. A selection from the UI is narrowed to that; nothing outside it is ever sent.
 */
@Component
@RequiredArgsConstructor
public class SealedSyncScope {

    /** Ids allowed for a sealed-only target. */
    public record Scope(Set<String> workflowIds, Set<String> projectIds, Set<String> skillIds, Set<String> chainIds) {
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
        for (Workflow workflow : workflowRepository.findSealedWithSkills()) {
            WorkflowSealVerifier.Verdict verdict = verifier.verify(workflow);
            if (!verdict.intact()) {
                continue;
            }
            workflowIds.add(workflow.getId());
            if (workflow.getProject() != null) {
                projectIds.add(workflow.getProject().getId());
            }
            skillIds.addAll(verdict.pins() != null ? verdict.pins().keySet() : usedSkillIds(workflow));
        }
        Set<String> chainIds = new HashSet<>();
        for (WorkflowChain chain : chainRepository.findAll()) {
            List<ChainEntry> entries = chain.getEntries();
            if (entries != null && !entries.isEmpty()
                    && entries.stream().allMatch(e -> workflowIds.contains(e.getWorkflowId()))) {
                chainIds.add(chain.getId());
            }
        }
        return new Scope(workflowIds, projectIds, skillIds, chainIds);
    }

    /** Narrows a selection (or "everything" = {@code null}) to the scope. */
    public SyncDefinitionsRequest restrict(SyncDefinitionsRequest request) {
        Scope scope = scope();
        return new SyncDefinitionsRequest(
                intersect(request == null ? null : request.projectIds(), scope.projectIds()),
                intersect(request == null ? null : request.workflowIds(), scope.workflowIds()),
                intersect(request == null ? null : request.skillIds(), scope.skillIds()),
                intersect(request == null ? null : request.chainIds(), scope.chainIds()));
    }

    /** Leaves only the items in the scope, so the preview shows exactly what a sync would send. */
    public SyncPreview restrict(SyncPreview preview) {
        Scope scope = scope();
        return new SyncPreview(
                only(preview.projects(), scope.projectIds()),
                only(preview.workflows(), scope.workflowIds()),
                only(preview.skills(), scope.skillIds()),
                only(preview.chains(), scope.chainIds()));
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

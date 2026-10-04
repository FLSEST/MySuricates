package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncPreviewItem;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowSealVerifier;
import com.automation.testsupport.FakeTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** What Prod may receive (TODO 84a78bb7): only intact seals, their projects/skills, chains made of them. */
@ExtendWith(MockitoExtension.class)
class SealedSyncScopeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private WorkflowSealVerifier verifier;

    private SealedSyncScope scope;

    @BeforeEach
    void setUp() {
        scope = new SealedSyncScope(workflowRepository, chainRepository, verifier, FakeTransactions.manager());
        Workflow intact = workflow("w-ok", "p-1");
        Workflow broken = workflow("w-broken", "p-2");
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of(intact, broken));
        when(verifier.verify(any())).thenAnswer(inv -> verdict(((Workflow) inv.getArgument(0)).getId().equals("w-ok")));
        when(chainRepository.findAll()).thenReturn(List.of(
                chain("c-ok", "w-ok"), chain("c-mixed", "w-ok", "w-unsealed"), chain("c-empty")));
    }

    @Test
    void everythingMeansOnlyIntactSealsTheirProjectSkillsAndPureChains() {
        SyncDefinitionsRequest r = scope.restrict((SyncDefinitionsRequest) null);

        assertThat(r.workflowIds()).containsExactly("w-ok");
        assertThat(r.projectIds()).containsExactly("p-1");
        assertThat(r.skillIds()).containsExactly("s-1");
        assertThat(r.chainIds()).containsExactly("c-ok");
    }

    @Test
    void aSelectionIsNarrowedNeverWidened() {
        SyncDefinitionsRequest r = scope.restrict(new SyncDefinitionsRequest(
                List.of("p-1", "p-2"), List.of("w-unsealed", "w-broken"), List.of(), List.of("c-mixed", "c-ok")));

        assertThat(r.workflowIds()).isEmpty();
        assertThat(r.projectIds()).containsExactly("p-1");
        assertThat(r.skillIds()).isEmpty();
        assertThat(r.chainIds()).containsExactly("c-ok");
    }

    @Test
    void previewShowsOnlyWhatWouldBeSent() {
        SyncPreview preview = new SyncPreview(
                List.of(item("p-1"), item("p-2")), List.of(item("w-ok"), item("w-unsealed")),
                List.of(item("s-1"), item("s-2")), null);

        SyncPreview restricted = scope.restrict(preview);

        assertThat(restricted.projects()).extracting(SyncPreviewItem::id).containsExactly("p-1");
        assertThat(restricted.workflows()).extracting(SyncPreviewItem::id).containsExactly("w-ok");
        assertThat(restricted.skills()).extracting(SyncPreviewItem::id).containsExactly("s-1");
        assertThat(restricted.chains()).isEmpty();
    }

    private static Workflow workflow(String id, String projectId) {
        Workflow w = Workflow.builder().name(id).project(Project.builder().build()).sealed(true).build();
        w.setId(id);
        w.getProject().setId(projectId);
        return w;
    }

    private static WorkflowSealVerifier.Verdict verdict(boolean intact) {
        return new WorkflowSealVerifier.Verdict(intact ? WorkflowSealVerifier.Problem.NONE : WorkflowSealVerifier.Problem.DEFINITION_CHANGED,
                "h", Set.of(), true, intact ? Map.of("s-1", "hash") : null, null, false, null, Map.of());
    }

    private static WorkflowChain chain(String id, String... workflowIds) {
        WorkflowChain chain = WorkflowChain.builder().name(id)
                .entries(java.util.Arrays.stream(workflowIds).map(w -> ChainEntry.builder().workflowId(w).build()).toList())
                .build();
        chain.setId(id);
        return chain;
    }

    private static SyncPreviewItem item(String id) {
        return new SyncPreviewItem(id, id, null, "NEW");
    }
}

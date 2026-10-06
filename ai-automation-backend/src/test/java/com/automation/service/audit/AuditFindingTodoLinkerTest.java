package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.audit.AuditCenterService.TodoLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The TODO links of a finding: create managed, link, unlink, maintain, read. */
@ExtendWith(MockitoExtension.class)
class AuditFindingTodoLinkerTest {

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ManagedTodoWriter managedTodoWriter;

    private AuditFindingTodoLinker linker() {
        return new AuditFindingTodoLinker(findingRepository, findingTodoRepository, todoRepository, managedTodoWriter);
    }

    @Test
    void createManagedTodo_createsOnlyWithoutManagedLink_andBuildsDescription() {
        AuditFinding finding = finding("f-1", "p-1");
        when(findingTodoRepository.findByFindingId("f-1")).thenReturn(List.of(link("f-1", "t-manual", false)));

        linker().createManagedTodo(finding);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(managedTodoWriter).create(any(), description.capture(), org.mockito.ArgumentMatchers.eq("run-9"),
                org.mockito.ArgumentMatchers.eq(true));
        assertThat(description.getValue()).startsWith("Beschreibung")
                .contains("Evidence: Beleg")
                .contains("[Audit finding](/reports?finding=f-1)")
                .contains("[Evidence run](/runs/run-9)");
    }

    @Test
    void createManagedTodo_usesFallbacksForMissingDescriptionAndEvidence() {
        AuditFinding finding = finding("f-1", "p-1");
        finding.setDescription(null);
        finding.setEvidence(null);
        when(findingTodoRepository.findByFindingId("f-1")).thenReturn(List.of());

        linker().createManagedTodo(finding);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(managedTodoWriter).create(any(), description.capture(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(description.getValue()).startsWith("Audit finding requires remediation.")
                .contains("Evidence: not provided");
    }

    @Test
    void createManagedTodo_doesNothingWhenAManagedLinkExists() {
        when(findingTodoRepository.findByFindingId("f-1")).thenReturn(List.of(link("f-1", "t-1", true)));

        linker().createManagedTodo(finding("f-1", "p-1"));

        verifyNoInteractions(managedTodoWriter);
    }

    @Test
    void linkTodo_savesAnUnmanagedLink() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("t-1", "p-1")));
        when(findingTodoRepository.findByFindingIdAndTodoId("f-1", "t-1")).thenReturn(Optional.empty());

        linker().linkTodo(finding("f-1", "p-1"), "t-1");

        ArgumentCaptor<AuditFindingTodo> saved = ArgumentCaptor.forClass(AuditFindingTodo.class);
        verify(findingTodoRepository).save(saved.capture());
        assertThat(saved.getValue().getFindingId()).isEqualTo("f-1");
        assertThat(saved.getValue().getTodoId()).isEqualTo("t-1");
        assertThat(saved.getValue().isManaged()).isFalse();
    }

    @Test
    void linkTodo_isIdempotentForAnExistingLink() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("t-1", "p-1")));
        when(findingTodoRepository.findByFindingIdAndTodoId("f-1", "t-1"))
                .thenReturn(Optional.of(link("f-1", "t-1", false)));

        linker().linkTodo(finding("f-1", "p-1"), "t-1");

        verify(findingTodoRepository, never()).save(any());
    }

    @Test
    void linkTodo_rejectsAnUnknownTodo() {
        when(todoRepository.findById("t-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> linker().linkTodo(finding("f-1", "p-1"), "t-x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void linkTodo_rejectsAnotherProject() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("t-1", "p-2")));

        assertThatThrownBy(() -> linker().linkTodo(finding("f-1", "p-1"), "t-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same project");
        verify(findingTodoRepository, never()).save(any());
    }

    @Test
    void unlinkTodo_deletesTheLinkAndClearsTheReferenceOfThatFinding() {
        AuditFindingTodo link = link("f-1", "t-1", true);
        Todo todo = todo("t-1", "p-1");
        todo.setReferenceType(ManagedTodoWriter.REFERENCE_TYPE);
        todo.setReferenceId("f-1");
        when(findingTodoRepository.findByFindingIdAndTodoId("f-1", "t-1")).thenReturn(Optional.of(link));
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));

        linker().unlinkTodo("f-1", "t-1");

        verify(findingTodoRepository).delete(link);
        assertThat(todo.getReferenceType()).isNull();
        assertThat(todo.getReferenceId()).isNull();
        verify(todoRepository).save(todo);
    }

    @Test
    void unlinkTodo_keepsAReferenceToAnotherFindingOrType() {
        Todo otherFinding = todo("t-1", "p-1");
        otherFinding.setReferenceType(ManagedTodoWriter.REFERENCE_TYPE);
        otherFinding.setReferenceId("f-other");
        Todo otherType = todo("t-2", "p-1");
        otherType.setReferenceType("workflow");
        otherType.setReferenceId("f-1");
        when(findingTodoRepository.findByFindingIdAndTodoId(any(), any())).thenReturn(Optional.empty());
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(otherFinding));
        when(todoRepository.findById("t-2")).thenReturn(Optional.of(otherType));

        linker().unlinkTodo("f-1", "t-1");
        linker().unlinkTodo("f-1", "t-2");

        assertThat(otherFinding.getReferenceId()).isEqualTo("f-other");
        assertThat(otherType.getReferenceType()).isEqualTo("workflow");
        verify(todoRepository, never()).save(any());
    }

    @Test
    void markAwaitingVerification_movesOnlyOpenFindings() {
        AuditFinding open = finding("f-open", "p-1");
        AuditFinding fixed = finding("f-fixed", "p-1");
        fixed.setStatus(AuditFindingStatus.RESOLVED);
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(
                link("f-open", "t-1", true), link("f-fixed", "t-1", true), link("f-gone", "t-1", true)));
        when(findingTodoRepository.findByFindingIdIn(List.of("f-open", "f-fixed", "f-gone"))).thenReturn(List.of(
                link("f-open", "t-1", true), link("f-fixed", "t-1", true), link("f-gone", "t-1", true)));
        // "f-gone" is deleted: findAllById simply does not return it.
        when(findingRepository.findAllById(List.of("f-open", "f-fixed", "f-gone"))).thenReturn(List.of(open, fixed));

        linker().markAwaitingVerification("t-1");

        assertThat(open.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(fixed.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        // e8ca26e9: saved in bulk, only the changed finding, no save per finding.
        verify(findingRepository).saveAll(List.of(open));
        verify(findingRepository, never()).save(any());
        // 5fb7b1e9: one bundled query over the links, not one per link.
        verify(findingRepository, times(1)).findAllById(any());
        verify(findingRepository, never()).findById(any());
    }

    /**
     * ab1ed481: The manually linked TODO is done, the managed one was released (ARCHIVED) —
     * the finding now waits for the next audit run just as after a completed managed TODO.
     */
    @Test
    void markAwaitingVerification_alsoForAManuallyLinkedTodo() {
        AuditFinding open = finding("f-1", "p-1");
        Todo archivedManaged = todo("t-managed", "p-1");
        archivedManaged.setStatus(Todo.Status.ARCHIVED);
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-manual"))).thenReturn(List.of(link("f-1", "t-manual", false)));
        when(findingTodoRepository.findByFindingIdIn(List.of("f-1"))).thenReturn(List.of(
                link("f-1", "t-manual", false), link("f-1", "t-managed", true)));
        when(todoRepository.findAllById(List.of("t-managed"))).thenReturn(List.of(archivedManaged));
        when(findingRepository.findAllById(List.of("f-1"))).thenReturn(List.of(open));

        linker().markAwaitingVerification("t-manual");

        assertThat(open.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        verify(findingRepository).saveAll(List.of(open));
    }

    /** As long as another linked TODO is still open, the work on the finding is not done. */
    @Test
    void markAwaitingVerification_keepsAFindingOpenWhileAnotherLinkedTodoIsOpen() {
        AuditFinding waitsForOther = finding("f-1", "p-1");
        AuditFinding done = finding("f-2", "p-1");
        Todo stillOpen = todo("t-other", "p-1");
        stillOpen.setStatus(Todo.Status.IN_PROGRESS);
        Todo alsoDone = todo("t-done", "p-1");
        alsoDone.setStatus(Todo.Status.DONE);
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(
                link("f-1", "t-1", false), link("f-2", "t-1", true)));
        when(findingTodoRepository.findByFindingIdIn(List.of("f-1", "f-2"))).thenReturn(List.of(
                link("f-1", "t-1", false), link("f-1", "t-other", true),
                link("f-2", "t-1", true), link("f-2", "t-done", false)));
        when(todoRepository.findAllById(List.of("t-other", "t-done"))).thenReturn(List.of(stillOpen, alsoDone));
        when(findingRepository.findAllById(List.of("f-1", "f-2"))).thenReturn(List.of(waitsForOther, done));

        linker().markAwaitingVerification("t-1");

        assertThat(waitsForOther.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(done.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        verify(findingRepository).saveAll(List.of(done));
    }

    /**
     * Bulk closing (review of ab1ed481): several TODOs become DONE at once. A finding to which only
     * these TODOs are attached waits; one with a further open TODO stays OPEN. One query per stage.
     */
    @Test
    void markAwaitingVerification_forSeveralTodosAtOnce() {
        AuditFinding bothDone = finding("f-1", "p-1");
        AuditFinding stillWorkedOn = finding("f-2", "p-1");
        Todo open = todo("t-3", "p-1");
        open.setStatus(Todo.Status.OPEN);
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1", "t-2"))).thenReturn(List.of(
                link("f-1", "t-1", true), link("f-1", "t-2", false), link("f-2", "t-2", false)));
        when(findingTodoRepository.findByFindingIdIn(List.of("f-1", "f-2"))).thenReturn(List.of(
                link("f-1", "t-1", true), link("f-1", "t-2", false), link("f-2", "t-2", false),
                link("f-2", "t-3", true)));
        when(todoRepository.findAllById(List.of("t-3"))).thenReturn(List.of(open));
        when(findingRepository.findAllById(List.of("f-1", "f-2"))).thenReturn(List.of(bothDone, stillWorkedOn));

        linker().markAwaitingVerification(List.of("t-1", "t-2"));

        assertThat(bothDone.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(stillWorkedOn.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        verify(findingRepository).saveAll(List.of(bothDone));
        verify(findingTodoRepository, times(1)).findByTodoIdIn(any());
        verify(findingTodoRepository, never()).findByTodoId(any());
    }

    @Test
    void markAwaitingVerification_savesNothingWhenNoLinkedFindingIsOpen() {
        AuditFinding fixed = finding("f-fixed", "p-1");
        fixed.setStatus(AuditFindingStatus.RESOLVED);
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(link("f-fixed", "t-1", true)));
        when(findingTodoRepository.findByFindingIdIn(List.of("f-fixed"))).thenReturn(List.of(link("f-fixed", "t-1", true)));
        when(findingRepository.findAllById(List.of("f-fixed"))).thenReturn(List.of(fixed));

        linker().markAwaitingVerification("t-1");

        verify(findingRepository, never()).saveAll(any());
        verify(findingRepository, never()).save(any());
    }

    @Test
    void markAwaitingVerification_withoutLinksQueriesNoFindings() {
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of());

        linker().markAwaitingVerification("t-1");

        verifyNoInteractions(findingRepository, todoRepository);
    }

    @Test
    void todoLinks_groupsByFinding_andSkipsLinksToDeletedTodos() {
        when(findingTodoRepository.findByFindingIdIn(List.of("f-1", "f-2"))).thenReturn(List.of(
                link("f-1", "t-1", true), link("f-1", "t-2", false), link("f-2", "t-gone", true)));
        Todo t1 = todo("t-1", "p-1");
        Todo t2 = todo("t-2", "p-1");
        t2.setStatus(Todo.Status.DONE);
        when(todoRepository.findAllById(List.of("t-1", "t-2", "t-gone"))).thenReturn(List.of(t1, t2));

        Map<String, List<TodoLink>> links = linker().todoLinks(List.of("f-1", "f-2"));

        assertThat(links).containsOnlyKeys("f-1");
        assertThat(links.get("f-1")).containsExactly(
                new TodoLink("t-1", "Titel t-1", t1.getStatus(), true),
                new TodoLink("t-2", "Titel t-2", Todo.Status.DONE, false));
    }

    @Test
    void todoLinks_withoutFindingsQueriesNothing() {
        assertThat(linker().todoLinks(List.of())).isEmpty();
        verifyNoInteractions(findingTodoRepository, todoRepository);
    }

    private static AuditFinding finding(String id, String projectId) {
        return AuditFinding.builder().id(id).projectId(projectId).description("Beschreibung").evidence("Beleg")
                .lastSeenRunId("run-9").build();
    }

    private static Todo todo(String id, String projectId) {
        return Todo.builder().id(id).title("Titel " + id).projectId(projectId).build();
    }

    private static AuditFindingTodo link(String findingId, String todoId, boolean managed) {
        return AuditFindingTodo.builder().findingId(findingId).todoId(todoId).managed(managed).build();
    }
}

package com.automation.service.audit;

import com.automation.entity.ActivityLog;
import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Das verwaltete TODO eines Findings: einmal anlegen, danach am Status der Findings ausrichten. */
@ExtendWith(MockitoExtension.class)
class ManagedTodoWriterTest {

    private static final String FINDING_ID = "f-1";

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ActivityLogService activityLog;

    private ManagedTodoWriter writer() {
        return new ManagedTodoWriter(findingRepository, findingTodoRepository, todoRepository, eventPublisher, activityLog);
    }

    /** Das TODO, das {@code create} speicherte (save je neuem TODO), mit vergebener ID. */
    private void stubTodoSave(String id) {
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> {
            Todo t = inv.getArgument(0);
            if (t.getId() == null) t.setId(id);
            return t;
        });
    }

    private Todo savedTodo() {
        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void ensureAll_reusesTheExistingManagedTodo_andIgnoresManualLinks() {
        Todo existing = todo("t-managed", Todo.Status.OPEN);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(
                link("t-manual", false), link("t-managed", true)));
        when(todoRepository.findAllById(List.of("t-managed"))).thenReturn(List.of(existing));

        writer().ensureAll(List.of(finding(FINDING_ID, AuditFindingStatus.OPEN)), report());

        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void ensureAll_createsTodoLinkAndEvent_fromFindingAndReport() {
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());
        stubTodoSave("t-new");

        writer().ensureAll(List.of(finding(FINDING_ID, AuditFindingStatus.OPEN)), report());

        Todo created = savedTodo();
        assertThat(created.getTitle()).isEqualTo("Shell-Aufruf ohne Escaping");
        assertThat(created.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(created.getPriority()).isEqualTo(Todo.Priority.CRITICAL);
        assertThat(created.getTags()).isEqualTo("[\"audit\",\"audit:mysuricates.security\",\"auto:workflow\"]");
        assertThat(created.getProjectId()).isEqualTo("p-1");
        assertThat(created.getSource()).isEqualTo(Todo.Source.WORKFLOW);
        assertThat(created.getSourceAgent()).isEqualTo(ManagedTodoWriter.SOURCE_AGENT);
        assertThat(created.getWorkflowRunId()).as("Run des Reports, nicht des Findings").isEqualTo("run-report");
        assertThat(created.getReferenceType()).isEqualTo(ManagedTodoWriter.REFERENCE_TYPE);
        assertThat(created.getReferenceId()).isEqualTo(FINDING_ID);
        assertThat(created.getDescription())
                .startsWith("Eingaben landen ungeprueft in der Shell.")
                .contains("Evidence: ContainerShellService.java:42")
                .contains("[Audit finding](/reports?finding=f-1)")
                .contains("[Evidence run](/workflows/wf-1/run/run-report)");

        ArgumentCaptor<AuditFindingTodo> savedLink = ArgumentCaptor.forClass(AuditFindingTodo.class);
        verify(findingTodoRepository).save(savedLink.capture());
        assertThat(savedLink.getValue().getFindingId()).isEqualTo(FINDING_ID);
        assertThat(savedLink.getValue().getTodoId()).isEqualTo("t-new");
        assertThat(savedLink.getValue().isManaged()).isTrue();
        assertThat(savedLink.getValue().isManual()).as("the ingest creates automatic TODOs").isFalse();
        ArgumentCaptor<TodoCreatedEvent> event = ArgumentCaptor.forClass(TodoCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getTodo()).isSameAs(created);
    }

    @Test
    void ensureAll_replacesADanglingManagedLink() {
        AuditFindingTodo dangling = link("t-gone", true);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(dangling));
        when(todoRepository.findAllById(List.of("t-gone"))).thenReturn(List.of());
        stubTodoSave("t-new");

        writer().ensureAll(List.of(finding(FINDING_ID, AuditFindingStatus.OPEN)), report());

        verify(findingTodoRepository).deleteAll(List.of(dangling));
        verify(todoRepository).save(any(Todo.class));
    }

    @Test
    void ensureAll_describesAFindingWithoutDescriptionOrEvidence() {
        AuditFinding finding = finding(FINDING_ID, AuditFindingStatus.OPEN);
        finding.setDescription(null);
        finding.setEvidence(null);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());
        stubTodoSave("t-new");

        writer().ensureAll(List.of(finding), report());

        assertThat(savedTodo().getDescription())
                .startsWith("Audit finding requires remediation.")
                .contains("Evidence: not provided");
    }

    /**
     * Folgebefund zu b2d65a20: Verknuepfungen und vorhandene TODOs aller Findings eines Reports kommen aus je
     * einer Abfrage, nicht aus findByFindingId und findById je Finding. Angelegt wird nur, was fehlt.
     */
    @Test
    void ensureAll_loadsLinksAndTodosOnceForAllFindings() {
        AuditFinding withTodo = finding(FINDING_ID, AuditFindingStatus.OPEN);
        AuditFinding dangling = finding("f-2", AuditFindingStatus.OPEN);
        AuditFinding fresh = finding("f-3", AuditFindingStatus.OPEN);
        AuditFindingTodo kept = link("t-1", true);
        AuditFindingTodo gone = AuditFindingTodo.builder().findingId("f-2").todoId("t-gone").managed(true).build();
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID, "f-2", "f-3"))).thenReturn(List.of(kept, gone));
        when(todoRepository.findAllById(List.of("t-1", "t-gone"))).thenReturn(List.of(todo("t-1", Todo.Status.OPEN)));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        writer().ensureAll(List.of(withTodo, dangling, fresh), report());

        ArgumentCaptor<Todo> created = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, times(2)).save(created.capture());
        assertThat(created.getAllValues()).extracting(Todo::getReferenceId).containsExactly("f-2", "f-3");
        verify(findingTodoRepository).deleteAll(List.of(gone));
        verify(findingTodoRepository, times(1)).findByFindingIdIn(any());
        verify(findingTodoRepository, never()).findByFindingId(any());
        verify(todoRepository, times(1)).findAllById(any());
        verify(todoRepository, never()).findById(any());
    }

    @Test
    void ensureAll_withoutFindingsQueriesNothing() {
        writer().ensureAll(List.of(), report());

        verifyNoInteractions(findingTodoRepository, todoRepository, eventPublisher);
    }

    @Test
    void reconcile_withoutTouchedFindings_doesNothing() {
        writer().reconcile(List.of());

        verifyNoInteractions(findingTodoRepository, todoRepository, findingRepository);
    }

    @Test
    void reconcile_closesTheTodoOnceEveryLinkedFindingIsTerminal() {
        AuditFinding resolved = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        AuditFinding dismissed = finding("f-2", AuditFindingStatus.DISMISSED);
        Todo todo = todo("t-1", Todo.Status.IN_PROGRESS);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-1").build(),
                AuditFindingTodo.builder().findingId("f-2").todoId("t-1").build()));
        when(findingRepository.findAllById(any())).thenReturn(List.of(resolved, dismissed));

        writer().reconcile(List.of(resolved, resolved));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).saveAll(List.of(todo));
    }

    @Test
    void reconcile_keepsTheTodoOpenWhileOneLinkedFindingIsOpen() {
        AuditFinding resolved = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        AuditFinding open = finding("f-2", AuditFindingStatus.OPEN);
        Todo todo = todo("t-1", Todo.Status.OPEN);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-1").build(),
                AuditFindingTodo.builder().findingId("f-2").todoId("t-1").build()));
        when(findingRepository.findAllById(any())).thenReturn(List.of(resolved, open));

        writer().reconcile(List.of(resolved));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    @Test
    void reconcile_reopensADoneTodoWhenAFindingComesBack_andPointsItAtTheNewRun() {
        AuditFinding back = finding(FINDING_ID, AuditFindingStatus.OPEN);
        back.setLastSeenRunId("run-back");
        Todo todo = todo("t-1", Todo.Status.DONE);
        todo.setWorkflowRunId("run-old");
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(link("t-1", true)));
        when(findingRepository.findAllById(any())).thenReturn(List.of(back));

        writer().reconcile(List.of(back));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(todo.getWorkflowRunId()).isEqualTo("run-back");
        verify(todoRepository).saveAll(List.of(todo));
    }

    @Test
    void reconcile_skipsManualLinksAndVanishedTodos() {
        AuditFinding resolved = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID)))
                .thenReturn(List.of(link("t-manual", false), link("t-gone", true)));
        when(todoRepository.findAllById(List.of("t-gone"))).thenReturn(List.of());

        writer().reconcile(List.of(resolved));

        verify(todoRepository).findAllById(List.of("t-gone")); // die manuelle Verknüpfung wird nicht geladen
        verifyNoInteractions(findingRepository);
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    /** ba0dcb3a: ein Finding unter der TODO-Schwelle gibt sein TODO frei — archiviert, nicht DONE. */
    @Test
    void reconcile_releasesTheTodo_whenItsOpenFindingFellBelowHigh() {
        AuditFinding medium = finding(FINDING_ID, AuditFindingStatus.OPEN);
        medium.setSeverity(AuditSeverity.MEDIUM);
        Todo todo = todo("t-1", Todo.Status.OPEN);
        todo.setReferenceType(ManagedTodoWriter.REFERENCE_TYPE);
        todo.setReferenceId(FINDING_ID);
        AuditFindingTodo managedLink = link("t-1", true);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(managedLink));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(managedLink));
        when(findingRepository.findAllById(any())).thenReturn(List.of(medium));

        writer().reconcile(List.of(medium));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        assertThat(todo.getReferenceType()).isNull();
        assertThat(todo.getReferenceId()).isNull();
        verify(findingTodoRepository).deleteAll(List.of(managedLink));
        verify(todoRepository).saveAll(List.of(todo));
        verify(activityLog).info(eq(ActivityLog.LogCategory.EXECUTION), contains("Shell-Aufruf ohne Escaping"), any());
    }

    @Test
    void reconcile_archivesInsteadOfReopening_whenAFindingReturnsBelowHigh() {
        AuditFinding back = finding(FINDING_ID, AuditFindingStatus.OPEN);
        back.setSeverity(AuditSeverity.LOW);
        Todo todo = todo("t-1", Todo.Status.DONE);
        AuditFindingTodo managedLink = link("t-1", true);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(managedLink));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(managedLink));
        when(findingRepository.findAllById(any())).thenReturn(List.of(back));

        writer().reconcile(List.of(back));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        verify(findingTodoRepository).deleteAll(List.of(managedLink));
    }

    /** f2ce6c50: a TODO created by hand for a finding below HIGH was a deliberate decision, not the threshold's. */
    @Test
    void reconcile_reopensAManualTodoBelowHigh_whenItsFindingComesBack() {
        AuditFinding back = finding(FINDING_ID, AuditFindingStatus.OPEN);
        back.setSeverity(AuditSeverity.LOW);
        back.setLastSeenRunId("run-back");
        Todo todo = todo("t-1", Todo.Status.DONE);
        AuditFindingTodo manualLink = manualLink("t-1");
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(manualLink));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(manualLink));
        when(findingRepository.findAllById(any())).thenReturn(List.of(back));

        writer().reconcile(List.of(back));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(todo.getWorkflowRunId()).isEqualTo("run-back");
        verify(findingTodoRepository, never()).deleteAll(any());
        verifyNoInteractions(activityLog);
    }

    @Test
    void reconcile_keepsAManualTodoBelowHighLinked_whileItsFindingStaysOpen() {
        AuditFinding medium = finding(FINDING_ID, AuditFindingStatus.OPEN);
        medium.setSeverity(AuditSeverity.MEDIUM);
        Todo todo = todo("t-1", Todo.Status.OPEN);
        AuditFindingTodo manualLink = manualLink("t-1");
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(manualLink));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(manualLink));
        when(findingRepository.findAllById(any())).thenReturn(List.of(medium));

        writer().reconcile(List.of(medium));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        verify(findingTodoRepository, never()).deleteAll(any());
        verify(todoRepository, never()).saveAll(any());
    }

    @Test
    void create_marksTheLinkManual_whenTheTodoIsCreatedByHand() {
        stubTodoSave("t-new");

        writer().create(finding(FINDING_ID, AuditFindingStatus.OPEN), "Beschreibung", "run-1", true);

        ArgumentCaptor<AuditFindingTodo> savedLink = ArgumentCaptor.forClass(AuditFindingTodo.class);
        verify(findingTodoRepository).save(savedLink.capture());
        assertThat(savedLink.getValue().isManaged()).isTrue();
        assertThat(savedLink.getValue().isManual()).isTrue();
    }

    /** An einem IN_PROGRESS-TODO arbeitet jemand — das bleibt, wie es ist. */
    @Test
    void reconcile_leavesAnInProgressTodoAlone_evenBelowHigh() {
        AuditFinding medium = finding(FINDING_ID, AuditFindingStatus.OPEN);
        medium.setSeverity(AuditSeverity.MEDIUM);
        Todo todo = todo("t-1", Todo.Status.IN_PROGRESS);
        AuditFindingTodo managedLink = link("t-1", true);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(managedLink));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(managedLink));
        when(findingRepository.findAllById(any())).thenReturn(List.of(medium));

        writer().reconcile(List.of(medium));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        verify(findingTodoRepository, never()).deleteAll(any());
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    @Test
    void reconcile_keepsTheTodo_whileAnotherLinkedOpenFindingIsStillHigh() {
        AuditFinding medium = finding(FINDING_ID, AuditFindingStatus.OPEN);
        medium.setSeverity(AuditSeverity.MEDIUM);
        AuditFinding high = finding("f-2", AuditFindingStatus.OPEN);
        high.setSeverity(AuditSeverity.HIGH);
        Todo todo = todo("t-1", Todo.Status.OPEN);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findAllById(List.of("t-1"))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-1"))).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-1").managed(true).build(),
                AuditFindingTodo.builder().findingId("f-2").todoId("t-1").managed(true).build()));
        when(findingRepository.findAllById(any())).thenReturn(List.of(medium, high));

        writer().reconcile(List.of(medium));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        verify(findingTodoRepository, never()).deleteAll(any());
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    /**
     * Ein TODO mit mehreren verwalteten Verknüpfungen wird einmal abgeglichen (be14db43). Vorher lief
     * der Abgleich je Verknüpfung: der erste Durchlauf gab das TODO frei (ARCHIVED, verwaltete
     * Verknüpfungen gelöscht), der zweite sah nur noch die manuelle Verknüpfung auf ein erledigtes
     * Finding und setzte das TODO auf DONE, als wäre etwas behoben.
     *
     * <p>Die Verknüpfungen liegen in einem Speicher, den {@code delete} wirklich ändert und aus dem
     * die einzelnen wie die gebündelten Abfragen lesen. So hält der Test das Verhalten fest; die
     * Zahl der Abfragen prüft {@link #reconcile_loadsTodosLinksAndFindingsInOneQueryEach}.
     */
    @Test
    void reconcile_releasesATodoWithSeveralManagedLinksOnce_andKeepsTheManualLink() {
        AuditFinding f1 = finding(FINDING_ID, AuditFindingStatus.OPEN);
        f1.setSeverity(AuditSeverity.MEDIUM);
        AuditFinding f2 = finding("f-2", AuditFindingStatus.RESOLVED);
        AuditFinding f3 = finding("f-3", AuditFindingStatus.RESOLVED);
        Todo todo = todo("t-1", Todo.Status.OPEN);
        AuditFindingTodo l1 = AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-1").managed(true).build();
        AuditFindingTodo l2 = AuditFindingTodo.builder().findingId("f-2").todoId("t-1").managed(true).build();
        AuditFindingTodo l3 = AuditFindingTodo.builder().findingId("f-3").todoId("t-1").managed(false).build();
        List<AuditFindingTodo> links = storedLinks(todo, List.of(l1, l2, l3), f1, f2, f3);

        writer().reconcile(List.of(f1, f2));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        verify(todoRepository, times(1)).saveAll(List.of(todo));
        verify(findingTodoRepository).deleteAll(List.of(l1, l2));
        assertThat(links).containsExactly(l3);
    }

    /** Verknüpfungen, TODO und Findings in einem Speicher: beide Abfrageformen lesen daraus, delete ändert ihn. */
    private List<AuditFindingTodo> storedLinks(Todo todo, List<AuditFindingTodo> initial, AuditFinding... findings) {
        List<AuditFindingTodo> links = new ArrayList<>(initial);
        Map<String, AuditFinding> findingsById = Arrays.stream(findings)
                .collect(Collectors.toMap(AuditFinding::getId, f -> f));
        lenient().when(findingTodoRepository.findByFindingIdIn(any())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return links.stream().filter(l -> ids.contains(l.getFindingId())).toList();
        });
        lenient().when(findingTodoRepository.findByTodoId(anyString())).thenAnswer(inv -> {
            String todoId = inv.getArgument(0);
            return links.stream().filter(l -> l.getTodoId().equals(todoId)).toList();
        });
        lenient().when(findingTodoRepository.findByTodoIdIn(any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return links.stream().filter(l -> ids.contains(l.getTodoId())).toList();
        });
        lenient().doAnswer(inv -> {
            links.remove((AuditFindingTodo) inv.getArgument(0));
            return null;
        }).when(findingTodoRepository).delete(any(AuditFindingTodo.class));
        lenient().doAnswer(inv -> {
            inv.<Iterable<AuditFindingTodo>>getArgument(0).forEach(links::remove);
            return null;
        }).when(findingTodoRepository).deleteAll(any());
        lenient().when(todoRepository.findById(todo.getId())).thenReturn(Optional.of(todo));
        lenient().when(todoRepository.findAllById(any())).thenReturn(List.of(todo));
        lenient().when(findingRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(findingsById.get((String) inv.getArgument(0))));
        lenient().when(findingRepository.findAllById(any())).thenAnswer(inv -> {
            List<AuditFinding> found = new ArrayList<>();
            for (String id : inv.<Iterable<String>>getArgument(0)) {
                if (findingsById.containsKey(id)) found.add(findingsById.get(id));
            }
            return found;
        });
        return links;
    }

    /** be14db43: je Stufe eine Abfrage, egal wie viele Findings und TODOs der Ingest berührt. */
    @Test
    void reconcile_loadsTodosLinksAndFindingsInOneQueryEach() {
        AuditFinding a = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        AuditFinding b = finding("f-2", AuditFindingStatus.RESOLVED);
        AuditFinding c = finding("f-3", AuditFindingStatus.OPEN);
        Todo todoAb = todo("t-ab", Todo.Status.OPEN);
        Todo todoC = todo("t-c", Todo.Status.DONE);
        AuditFindingTodo linkA = AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-ab").managed(true).build();
        AuditFindingTodo linkB = AuditFindingTodo.builder().findingId("f-2").todoId("t-ab").managed(true).build();
        AuditFindingTodo linkC = AuditFindingTodo.builder().findingId("f-3").todoId("t-c").managed(true).build();
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID, "f-2", "f-3")))
                .thenReturn(List.of(linkA, linkB, linkC));
        when(todoRepository.findAllById(List.of("t-ab", "t-c"))).thenReturn(List.of(todoAb, todoC));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-ab", "t-c"))).thenReturn(List.of(linkA, linkB, linkC));
        when(findingRepository.findAllById(any())).thenReturn(List.of(a, b, c));

        writer().reconcile(List.of(a, b, c));

        assertThat(todoAb.getStatus()).as("beide Findings terminal").isEqualTo(Todo.Status.DONE);
        assertThat(todoC.getStatus()).as("Finding kam zurück").isEqualTo(Todo.Status.OPEN);
        verify(findingTodoRepository, times(1)).findByFindingIdIn(any());
        verify(findingTodoRepository, times(1)).findByTodoIdIn(any());
        verify(todoRepository, times(1)).findAllById(any());
        verify(findingRepository, times(1)).findAllById(any());
        verify(todoRepository, never()).findById(any());
        verify(findingRepository, never()).findById(any());
        verify(findingTodoRepository, never()).findByTodoId(any());
        // b2d65a20: beide geaenderten TODOs in einem saveAll, kein save je TODO.
        verify(todoRepository, times(1)).saveAll(List.of(todoAb, todoC));
        verify(todoRepository, never()).save(any());
    }

    private static AuditFindingTodo link(String todoId, boolean managed) {
        return AuditFindingTodo.builder().findingId(FINDING_ID).todoId(todoId).managed(managed).build();
    }

    private static AuditFindingTodo manualLink(String todoId) {
        return AuditFindingTodo.builder().findingId(FINDING_ID).todoId(todoId).managed(true).manual(true).build();
    }

    private static Todo todo(String id, Todo.Status status) {
        return Todo.builder().id(id).title("Shell-Aufruf ohne Escaping").status(status)
                .priority(Todo.Priority.HIGH).projectId("p-1").build();
    }

    private static AuditReport report() {
        return AuditReport.builder().id("rep-1").projectId("p-1").workflowId("wf-1").workflowRunId("run-report")
                .auditKey("mysuricates.security").build();
    }

    private static AuditFinding finding(String id, AuditFindingStatus status) {
        return AuditFinding.builder().id(id).projectId("p-1").auditKey("mysuricates.security")
                .fingerprint("fp-" + id).ruleKey("shell.injection").subject("ContainerShellService")
                .severity(AuditSeverity.CRITICAL).title("Shell-Aufruf ohne Escaping")
                .description("Eingaben landen ungeprueft in der Shell.").evidence("ContainerShellService.java:42")
                .status(status).firstSeenReportId("rep-1").lastSeenReportId("rep-1")
                .firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-05T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .build();
    }
}

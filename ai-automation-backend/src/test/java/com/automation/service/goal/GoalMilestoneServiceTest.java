package com.automation.service.goal;

import com.automation.dto.projection.GoalMilestoneLinks;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithWorkPackages;
import com.automation.dto.projection.WorkPackageTodoCounts;
import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalMilestoneWorkPackageRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Meilensteine – Termine des ganzen Ziels, optional an Arbeitspakete geknuepft (TODO c60bf914 Ziele
 * Stufe 1: Ziel → Projekte → Arbeitspakete → TODOs, Meilensteine, Gliederung und Zeitstrahl): anlegen,
 * Verknuepfungen ersetzen, von Hand erreichen, die 400-Faelle und der Zustand in der Liste.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalMilestoneServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock private GoalRepository goalRepository;
    @Mock private GoalWorkPackageRepository workPackageRepository;
    @Mock private GoalMilestoneRepository milestoneRepository;
    @Mock private GoalMilestoneWorkPackageRepository linkRepository;
    @Mock private TodoRepository todoRepository;

    private GoalMilestoneService service;

    @BeforeEach
    void setUp() {
        service = new GoalMilestoneService(goalRepository, workPackageRepository, milestoneRepository, linkRepository,
                todoRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(milestoneRepository.save(any())).thenAnswer(inv -> {
            GoalMilestone m = inv.getArgument(0);
            if (m.getId() == null) m.setId("new");
            return m;
        });
    }

    @Test
    void create_readsADateAsTheEndOfThatDay_linksThePackages_andComputesTheState() {
        when(workPackageRepository.findIdsInGoal("g1", List.of("wp2", "wp1"))).thenReturn(List.of("wp1", "wp2"));
        when(todoRepository.countByWorkPackage(List.of("wp1", "wp2"))).thenReturn(List.of(
                new WorkPackageTodoCounts("wp1", 2, 2), new WorkPackageTodoCounts("wp2", 3, 1)));

        GoalMilestoneResponse created = service.create("g1",
                new CreateGoalMilestoneRequest("  Launch  ", "2026-10-15", List.of("wp2", "wp1", "wp2", " ")));

        assertThat(created.id()).isEqualTo("new");
        assertThat(created.title()).isEqualTo("Launch");
        assertThat(created.dueDate()).isEqualTo(Instant.parse("2026-10-15T23:59:59Z"));
        assertThat(created.workPackageIds()).as("ohne Doppelte und Leere, sortiert").containsExactly("wp1", "wp2");
        assertThat(created.packagesTotal()).isEqualTo(2);
        assertThat(created.packagesDone()).isEqualTo(1);
        assertThat(created.state()).isEqualTo(GoalDueState.OPEN);
        verify(linkRepository).link("new", "wp1");
        verify(linkRepository).link("new", "wp2");
    }

    @Test
    void create_withoutPackages_needsNoCounts_andARequiredDate() {
        GoalMilestoneResponse created = service.create("g1", new CreateGoalMilestoneRequest("Beta", "2026-09-29T12:00:00Z"));

        assertThat(created.workPackageIds()).isEmpty();
        assertThat(created.state()).as("in zwei Tagen").isEqualTo(GoalDueState.DUE_SOON);
        verify(todoRepository, never()).countByWorkPackage(any());
        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest("Beta", " ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Datum");
        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest("Beta", "bald")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest(" ", "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_withAForeignPackage_is400_andAnUnknownGoal_is404() {
        when(workPackageRepository.findIdsInGoal("g1", List.of("wp1", "fremd"))).thenReturn(List.of("wp1"));
        when(goalRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest("Launch", "2026-10-15", List.of("wp1", "fremd"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fremd");
        assertThatThrownBy(() -> service.create("nope", new CreateGoalMilestoneRequest("Launch", "2026-10-15")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(milestoneRepository, never()).save(any());
        verify(linkRepository, never()).link(anyString(), anyString());
    }

    @Test
    void update_replacesTheLinks_afterSaving() {
        GoalMilestone m = milestone("m", NOW.plus(Duration.ofDays(10)), null);
        when(milestoneRepository.findById("m")).thenReturn(Optional.of(m));
        when(workPackageRepository.findIdsInGoal("g1", List.of("wp3"))).thenReturn(List.of("wp3"));
        when(todoRepository.countByWorkPackage(List.of("wp3"))).thenReturn(List.of(new WorkPackageTodoCounts("wp3", 1, 1)));

        GoalMilestoneResponse updated = service.update("g1", "m",
                new UpdateGoalMilestoneRequest("Launch 2", "2026-10-20", List.of("wp3"), null));

        assertThat(updated.title()).isEqualTo("Launch 2");
        assertThat(updated.dueDate()).isEqualTo(Instant.parse("2026-10-20T23:59:59Z"));
        assertThat(updated.workPackageIds()).containsExactly("wp3");
        assertThat(updated.state()).as("das einzige Paket ist abgeschlossen").isEqualTo(GoalDueState.REACHED);
        assertThat(m.getReachedAt()).isEqualTo(NOW);
        InOrder order = inOrder(milestoneRepository, linkRepository);
        order.verify(milestoneRepository).save(m);
        order.verify(linkRepository).unlinkAll("m");
        order.verify(linkRepository).link("m", "wp3");
    }

    /** Ohne verknuepfte Pakete markiert ein Mensch den Meilenstein; mit Paketen ist {@code reached} ein 400. */
    @Test
    void update_reachedByHand_onlyWithoutPackages() {
        GoalMilestone manual = milestone("manual", NOW.minus(Duration.ofDays(1)), null);
        GoalMilestone linked = milestone("linked", NOW.minus(Duration.ofDays(1)), null);
        when(milestoneRepository.findById("manual")).thenReturn(Optional.of(manual));
        when(milestoneRepository.findById("linked")).thenReturn(Optional.of(linked));
        when(linkRepository.findWorkPackageIds("manual")).thenReturn(List.of());
        when(linkRepository.findWorkPackageIds("linked")).thenReturn(List.of("wp1"));

        GoalMilestoneResponse reached = service.update("g1", "manual", new UpdateGoalMilestoneRequest(null, null, null, true));
        assertThat(reached.state()).isEqualTo(GoalDueState.REACHED);
        assertThat(manual.getReachedAt()).isEqualTo(NOW);
        Instant first = manual.getReachedAt();
        service.update("g1", "manual", new UpdateGoalMilestoneRequest(null, null, null, true));
        assertThat(manual.getReachedAt()).as("nochmal true verschiebt den Zeitpunkt nicht").isEqualTo(first);
        GoalMilestoneResponse open = service.update("g1", "manual", new UpdateGoalMilestoneRequest(null, null, null, false));
        assertThat(open.state()).isEqualTo(GoalDueState.OVERDUE);
        assertThat(manual.getReachedAt()).isNull();

        assertThatThrownBy(() -> service.update("g1", "linked", new UpdateGoalMilestoneRequest(null, null, null, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Arbeitspaket");
        when(workPackageRepository.findIdsInGoal("g1", List.of("wp2"))).thenReturn(List.of("wp2"));
        assertThatThrownBy(() -> service.update("g1", "manual", new UpdateGoalMilestoneRequest(null, null, List.of("wp2"), false)))
                .as("Pakete und reached in einer Anfrage").isInstanceOf(IllegalArgumentException.class);
        assertThat(linked.getReachedAt()).isNull();

        GoalMilestoneResponse unlinked = service.update("g1", "linked", new UpdateGoalMilestoneRequest(null, null, List.of(), true));
        assertThat(unlinked.workPackageIds()).as("Pakete loesen und von Hand erreichen geht in einem Schritt").isEmpty();
        assertThat(unlinked.state()).isEqualTo(GoalDueState.REACHED);
        verify(linkRepository).unlinkAll("linked");
    }

    @Test
    void update_emptyDate_foreignPackage_andAMilestoneOfAnotherGoal_areRefused() {
        GoalMilestone m = milestone("m", NOW.plus(Duration.ofDays(10)), null);
        when(milestoneRepository.findById("m")).thenReturn(Optional.of(m));
        when(workPackageRepository.findIdsInGoal("g1", List.of("fremd"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.update("g1", "m", new UpdateGoalMilestoneRequest(null, "", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Datum");
        assertThatThrownBy(() -> service.update("g1", "m", new UpdateGoalMilestoneRequest(null, null, List.of("fremd"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("other-goal", "m", new UpdateGoalMilestoneRequest("x", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(linkRepository, never()).unlinkAll(anyString());
    }

    @Test
    void delete_unlinksThePackages_thenDeletes() {
        GoalMilestone m = milestone("m", NOW, null);
        when(milestoneRepository.findById("m")).thenReturn(Optional.of(m));

        service.delete("g1", "m");

        InOrder order = inOrder(linkRepository, milestoneRepository);
        order.verify(linkRepository).unlinkAll("m");
        order.verify(milestoneRepository).deleteById("m");
    }

    /** Die Liste rechnet Pakete und Meilensteine aus denselben TODO-Zeilen – drei Abfragen, keine je Paket. */
    @Test
    void list_and_snapshot_takeThePackageStatesFromTheTodoRows() {
        Goal goal = Goal.builder().id("g1").title("Release").build();
        GoalWorkPackage done = GoalWorkPackage.builder().id("wp1").goalId("g1").title("Doku").build();
        GoalWorkPackage open = GoalWorkPackage.builder().id("wp2").goalId("g1").title("Site").build();
        when(goalRepository.findWithWorkPackages("g1")).thenReturn(Optional.of(new GoalWithWorkPackages(goal, List.of(done, open))));
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "wp1"), row("t2", Todo.Status.OPEN, "wp2"), row("t3", Todo.Status.DONE, null)));
        GoalMilestone both = milestone("both", NOW.plus(Duration.ofDays(20)), null);
        GoalMilestone first = milestone("first", NOW.plus(Duration.ofDays(5)), null);
        when(milestoneRepository.findWithLinks("g1")).thenReturn(List.of(
                new GoalMilestoneLinks(both, List.of("wp1", "wp2")), new GoalMilestoneLinks(first, List.of("wp1"))));

        GoalSnapshot snapshot = service.snapshot("g1");

        assertThat(snapshot.milestones()).extracting(GoalMilestoneResponse::id).as("nach Datum").containsExactly("first", "both");
        assertThat(snapshot.milestones().get(0).state()).isEqualTo(GoalDueState.REACHED);
        assertThat(snapshot.milestones().get(1).state()).isEqualTo(GoalDueState.OPEN);
        assertThat(snapshot.milestones().get(1).packagesDone()).isEqualTo(1);
        assertThat(snapshot.workPackages()).extracting(p -> p.state()).containsExactly(GoalDueState.REACHED, GoalDueState.OPEN);
        assertThat(snapshot.progress().done()).isEqualTo(2);
        assertThat(service.list("g1")).extracting(GoalMilestoneResponse::id).containsExactly("first", "both");
        verify(todoRepository, never()).countByWorkPackage(any());

        when(goalRepository.findWithWorkPackages("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    private static GoalMilestone milestone(String id, Instant due, Instant reachedAt) {
        return GoalMilestone.builder().id(id).goalId("g1").title(id).dueDate(due).reachedAt(reachedAt).build();
    }

    private static GoalTodoRow row(String id, Todo.Status status, String workPackageId) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                null, NOW, NOW, null, workPackageId);
    }
}

package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunInterruptionTest {

    @Test
    void schreibtHerkunftUndUrsacheAlsNamenInDieMetadaten() {
        WorkflowRun run = new WorkflowRun();

        RunInterruption.record(run, RunStatus.RUNNING, RunInterruption.Cause.RESTART);

        assertThat(run.getMetadata())
                .containsEntry(RunInterruption.FROM_KEY, "RUNNING")
                .containsEntry(RunInterruption.BY_KEY, "RESTART");
    }

    @Test
    void behaeltVorhandeneMetadatenUndKopiertUnveraenderlicheMap() {
        WorkflowRun run = new WorkflowRun();
        Map<String, Object> immutable = Map.of("trigger", "SCHEDULE");
        run.setMetadata(immutable);

        RunInterruption.record(run, RunStatus.PAUSED, RunInterruption.Cause.STANDBY);

        assertThat(run.getMetadata())
                .isNotSameAs(immutable)
                .containsEntry("trigger", "SCHEDULE")
                .containsEntry(RunInterruption.FROM_KEY, "PAUSED")
                .containsEntry(RunInterruption.BY_KEY, "STANDBY");
        assertThat(immutable).containsOnlyKeys("trigger");
    }

    @Test
    void legtMetadatenAnWennKeineDaSind() {
        WorkflowRun run = new WorkflowRun();
        run.setMetadata(null);

        RunInterruption.record(run, RunStatus.RUNNING, RunInterruption.Cause.STANDBY);

        assertThat(run.getMetadata()).containsOnlyKeys(RunInterruption.FROM_KEY, RunInterruption.BY_KEY);
    }

    @Test
    void ueberschreibtEineFruehereUnterbrechung() {
        WorkflowRun run = new WorkflowRun();
        RunInterruption.record(run, RunStatus.PAUSED, RunInterruption.Cause.RESTART);

        RunInterruption.record(run, RunStatus.RUNNING, RunInterruption.Cause.STANDBY);

        assertThat(run.getMetadata())
                .containsEntry(RunInterruption.FROM_KEY, "RUNNING")
                .containsEntry(RunInterruption.BY_KEY, "STANDBY");
    }

    @Test
    void eventKopiertDieRunIdsUnveraenderlich() {
        List<String> ids = new ArrayList<>(List.of("a", "b"));

        RunInterruption.Event event = new RunInterruption.Event(ids, RunInterruption.Cause.RESTART);
        ids.add("c");

        assertThat(event.runIds()).containsExactly("a", "b");
        assertThatThrownBy(() -> event.runIds().add("d")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(event.cause()).isEqualTo(RunInterruption.Cause.RESTART);
    }

    @Test
    void eventLehntNullAlsRunIdsAb() {
        assertThatThrownBy(() -> new RunInterruption.Event(null, RunInterruption.Cause.STANDBY))
                .isInstanceOf(NullPointerException.class);
    }
}

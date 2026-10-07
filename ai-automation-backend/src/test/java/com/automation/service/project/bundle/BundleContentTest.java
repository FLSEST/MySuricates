package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleChainEntry;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundlePath;
import com.automation.dto.bundle.ProjectBundle.BundleProject;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.dto.bundle.ProjectBundle.BundleTarget;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BundleContentTest {

    private static final Instant AT = Instant.parse("2026-10-07T08:00:00Z");

    private static BundleDefinition definition() {
        return new BundleDefinition("Name", "Description", "icon",
                List.of(Map.of("type", "shell", "config", Map.of("command", "c"))), "i", "opus", "CLAUDE", "high",
                "1.0", Map.of("ctx", "d"), Map.of("workspace", Map.of("source_path", "m")), List.of("skill"));
    }

    private static ProjectBundle bundle() {
        BundleChain chain = new BundleChain("chain", "Chain", "chain desc", null, true, List.of(
                new BundleChainEntry("wf", "label", 1, false, Map.of("in", "e"), 5)));
        BundleSchedule workflowSchedule = new BundleSchedule(new BundleTarget("wf", null), "0 0 6 * * *", "CRON",
                AT, "1h", "sched desc", Map.of("in", "s"), "SKIP", true, 3, AT);
        BundleSchedule chainSchedule = new BundleSchedule(new BundleTarget(null, "chain"), "0 0 7 * * *", "CRON",
                null, null, null, Map.of("in", "t"), null, null, null, null);
        return new ProjectBundle(ProjectBundle.FORMAT, ProjectBundle.FORMAT_VERSION, AT, "0.7.8",
                new BundleProject("p", null, null, null, null, Map.of("k", "v")),
                List.of(new BundleWorkflow("wf", definition())), List.of(chain),
                List.of(workflowSchedule, chainSchedule),
                List.of(new BundlePath("@@path:1@@", "/x", 1, "h")), List.of("skill"), List.of("w"));
    }

    @Test
    void rewritesEveryContentPlaceAndReportsOwnerAndField() {
        List<String> seen = new ArrayList<>();

        ProjectBundle out = BundleContent.rewrite(bundle(), (value, owner, field) -> {
            seen.add(owner + " | " + field + " | " + value);
            return value.toUpperCase();
        });

        assertThat(seen).containsExactlyInAnyOrder(
                "Name | blocks[0].type | shell",
                "Name | blocks[0].config.command | c",
                "Name | instructions | i",
                "Name | defaultContext.ctx | d",
                "Name | metadata.workspace.source_path | m",
                "Chain | entries[0].inputContext.in | e",
                "schedule workflow:wf | inputContext.in | s",
                "schedule chain:chain | inputContext.in | t");
        assertThat(seen).contains("Name | " + BundleContent.SOURCE_PATH_FIELD + " | m");
        BundleDefinition d = out.workflows().get(0).definition();
        assertThat(d.instructions()).isEqualTo("I");
        assertThat(d.blocks().get(0)).isEqualTo(Map.of("type", "SHELL", "config", Map.of("command", "C")));
        assertThat(d.defaultContext()).isEqualTo(Map.of("ctx", "D"));
        assertThat(out.chains().get(0).entries().get(0).inputContext()).isEqualTo(Map.of("in", "E"));
        assertThat(out.schedules()).extracting(BundleSchedule::inputContext)
                .containsExactly(Map.of("in", "S"), Map.of("in", "T"));
    }

    @Test
    void namesKeysAndSettingsAreNeverTouched() {
        ProjectBundle in = bundle();

        ProjectBundle out = BundleContent.rewrite(in, (value, owner, field) -> "X");

        BundleDefinition d = out.workflows().get(0).definition();
        assertThat(d.name()).isEqualTo("Name");
        assertThat(d.description()).isEqualTo("Description");
        assertThat(List.of(d.icon(), d.model(), d.provider(), d.effort(), d.version()))
                .containsExactly("icon", "opus", "CLAUDE", "high", "1.0");
        assertThat(d.activatedSkillNames()).containsExactly("skill");
        assertThat(out.workflows().get(0).key()).isEqualTo("wf");
        BundleChain chain = out.chains().get(0);
        assertThat(List.of(chain.key(), chain.name(), chain.description())).containsExactly("chain", "Chain", "chain desc");
        BundleChainEntry entry = chain.entries().get(0);
        assertThat(entry.workflowKey()).isEqualTo("wf");
        assertThat(entry.label()).isEqualTo("label");
        assertThat(entry.sortOrder()).isEqualTo(1);
        assertThat(entry.stopOnFailure()).isFalse();
        assertThat(entry.timeoutMinutes()).isEqualTo(5);
        BundleSchedule s = out.schedules().get(0);
        assertThat(s.target()).isEqualTo(new BundleTarget("wf", null));
        assertThat(List.of(s.cronExpression(), s.scheduleType(), s.delayExpression(), s.description(),
                s.conflictBehavior())).containsExactly("0 0 6 * * *", "CRON", "1h", "sched desc", "SKIP");
        assertThat(s.scheduledAt()).isEqualTo(AT);
        assertThat(s.catchUpOnResume()).isTrue();
        assertThat(s.maxRuns()).isEqualTo(3);
        assertThat(s.endDate()).isEqualTo(AT);
        assertThat(out.project()).isEqualTo(in.project());
        assertThat(out.paths()).isEqualTo(in.paths());
        assertThat(out.skillNames()).isEqualTo(in.skillNames());
        assertThat(out.warnings()).isEqualTo(in.warnings());
        assertThat(out.exportedAt()).isEqualTo(AT);
        assertThat(out.appVersion()).isEqualTo("0.7.8");
    }

    @Test
    void scanVisitsWithoutChangingTheBundle() {
        ProjectBundle in = bundle();
        List<String> values = new ArrayList<>();

        BundleContent.scan(in, (value, owner, field) -> {
            values.add(value);
            return "changed";
        });

        assertThat(values).hasSize(8);
        assertThat(in.workflows().get(0).definition().instructions()).isEqualTo("i");
    }

    @Test
    void nullPartsStayNull() {
        assertThat(BundleContent.rewrite((BundleDefinition) null, (v, o, f) -> v)).isNull();

        BundleDefinition empty = new BundleDefinition("N", null, null, null, null, null, null, null, null, null,
                null, null);
        BundleDefinition out = BundleContent.rewrite(empty, (v, o, f) -> "X");

        assertThat(out.blocks()).isNull();
        assertThat(out.instructions()).isNull();
        assertThat(out.defaultContext()).isNull();
        assertThat(out.metadata()).isNull();
    }

    @Test
    void nullEntriesAndChainsOrSchedulesWithoutContentAreKeptAsTheyAre() {
        BundleChain noEntries = new BundleChain("c", "C", null, null, null, null);
        BundleChain nullEntry = new BundleChain("d", "D", null, null, null, Arrays.asList(
                null, new BundleChainEntry("wf", null, 0, null, null, null)));
        BundleSchedule noContext = new BundleSchedule(new BundleTarget("wf", null), "0 0 6 * * *", null, null, null,
                null, null, null, null, null, null);
        BundleSchedule noTarget = new BundleSchedule(null, null, null, null, null, null, Map.of("k", "v"), null,
                null, null, null);
        ProjectBundle in = new ProjectBundle(ProjectBundle.FORMAT, 1, null, null, null, Arrays.asList((BundleWorkflow) null),
                Arrays.asList(noEntries, nullEntry, null), Arrays.asList(noContext, null, noTarget), List.of(), List.of(),
                List.of());
        List<String> owners = new ArrayList<>();

        ProjectBundle out = BundleContent.rewrite(in, (value, owner, field) -> {
            owners.add(owner);
            return value;
        });

        assertThat(out.workflows()).containsExactly((BundleWorkflow) null);
        assertThat(out.chains()).hasSize(3);
        assertThat(out.chains().get(2)).isNull();
        assertThat(out.chains().get(0)).isSameAs(noEntries);
        assertThat(out.chains().get(1).entries()).hasSize(2).first().isNull();
        assertThat(out.chains().get(1).entries().get(1).inputContext()).isNull();
        assertThat(out.schedules().get(0)).isSameAs(noContext);
        assertThat(out.schedules().get(1)).isNull();
        assertThat(owners).containsExactly("schedule ?");
    }
}

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleValidatorTest {

    private static final BundleProject PROJECT = new BundleProject("Shop", null, null, "CODE", null, null);

    private static BundleWorkflow workflow(String key, String name, String instructions,
                                           List<Map<String, Object>> blocks) {
        return new BundleWorkflow(key, new BundleDefinition(name, null, null, blocks, instructions, null, null, null,
                null, null, null, null));
    }

    private static BundleChain chain(String key, String... workflowKeys) {
        List<BundleChainEntry> entries = new ArrayList<>();
        for (String k : workflowKeys) {
            entries.add(new BundleChainEntry(k, null, 0, null, null, null));
        }
        return new BundleChain(key, key.toUpperCase(), null, null, null, entries);
    }

    private static BundleSchedule schedule(BundleTarget target, String type, String cron, String conflict) {
        return new BundleSchedule(target, cron, type, null, null, null, null, conflict, null, null, null);
    }

    private static ProjectBundle bundle(BundleProject project, List<BundleWorkflow> workflows, List<BundleChain> chains,
                                        List<BundleSchedule> schedules, List<BundlePath> paths) {
        return new ProjectBundle(ProjectBundle.FORMAT, ProjectBundle.FORMAT_VERSION, null, null, project, workflows,
                chains, schedules, paths, List.of(), List.of());
    }

    /** A valid bundle using every kind of reference. */
    private static ProjectBundle valid() {
        return bundle(PROJECT,
                List.of(workflow("tests", "Tests", "cd @@path:1@@", List.of(
                                Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "@@workflow:report@@")))),
                        workflow("report", "Report", null, null)),
                List.of(chain("all", "tests", "report")),
                List.of(schedule(new BundleTarget(null, "all"), null, "0 0 6 * * *", "skip"),
                        schedule(new BundleTarget("tests", null), "once", null, "OVERLAP")),
                List.of(new BundlePath("@@path:1@@", "/home/u/shop", 1, "h")));
    }

    @Test
    void acceptsAValidBundle() {
        assertThatCode(() -> BundleValidator.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheProjectTypeInAnyCaseAndNoTypeAtAll() {
        ProjectBundle lowerCase = bundle(new BundleProject("Shop", null, null, "research", null, null),
                List.of(), List.of(), List.of(), List.of());
        ProjectBundle noType = bundle(new BundleProject("Shop", null, null, null, null, null),
                List.of(), List.of(), List.of(), List.of());

        assertThatCode(() -> BundleValidator.validate(lowerCase)).doesNotThrowAnyException();
        assertThatCode(() -> BundleValidator.validate(noType)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingBundleFormatAndVersionBeforeLookingInside() {
        ProjectBundle wrongFormat = new ProjectBundle("other", 1, null, null, null, null, null, null, null, null, null);
        ProjectBundle noVersion = new ProjectBundle(ProjectBundle.FORMAT, null, null, null, null, null, null, null,
                null, null, null);
        ProjectBundle newerVersion = new ProjectBundle(ProjectBundle.FORMAT, 2, null, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> BundleValidator.validate(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid bundle: 'bundle' is missing");
        assertThatThrownBy(() -> BundleValidator.validate(wrongFormat)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid bundle: format must be 'mysuricates-project-bundle', got 'other'");
        assertThatThrownBy(() -> BundleValidator.validate(noVersion)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported bundle formatVersion null (supported: 1)");
        assertThatThrownBy(() -> BundleValidator.validate(newerVersion)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported bundle formatVersion 2 (supported: 1)");
    }

    @Test
    void reportsProjectProblems() {
        assertThatThrownBy(() -> BundleValidator.validate(bundle(null, List.of(), List.of(), List.of(), List.of())))
                .hasMessage("Invalid bundle: project.name is required");
        assertThatThrownBy(() -> BundleValidator.validate(bundle(new BundleProject(" ", null, null, "nope", null, null),
                List.of(), List.of(), List.of(), List.of())))
                .hasMessage("Invalid bundle: project.name is required");
        assertThatThrownBy(() -> BundleValidator.validate(bundle(new BundleProject("Shop", null, null, "nope", null, null),
                List.of(), List.of(), List.of(), List.of())))
                .hasMessage("Invalid bundle: project.type 'nope' is unknown");
    }

    @Test
    void reportsWorkflowProblemsAllAtOnce() {
        List<BundleWorkflow> workflows = Arrays.asList(
                null,
                new BundleWorkflow(" ", null),
                workflow("a", "A", null, List.of(Map.of("type", "simple", "config",
                        Map.of("command", "see @@workflow:ghost@@")))),
                workflow("a", " ", null, null),
                new BundleWorkflow("b", null));

        assertThatThrownBy(() -> BundleValidator.validate(bundle(PROJECT, workflows, List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid bundle: workflows[0].key is required; workflows[1].key is required; "
                        + "duplicate workflow key 'a'; workflow 'a': definition.name is required; "
                        + "workflow 'b': definition.name is required; "
                        + "workflow 'a' references unknown workflow key 'ghost'");
    }

    @Test
    void reportsChainProblems() {
        List<BundleChain> chains = Arrays.asList(
                null,
                new BundleChain("", "X", null, null, null, null),
                chain("c", "w"),
                new BundleChain("c", " ", null, null, null, List.of()),
                chain("d", "ghost"),
                new BundleChain("e", "E", null, null, null, Arrays.asList((BundleChainEntry) null)));

        assertThatThrownBy(() -> BundleValidator.validate(bundle(PROJECT, List.of(workflow("w", "W", null, null)),
                chains, List.of(), List.of())))
                .hasMessage("Invalid bundle: chains[0].key is required; chains[1].key is required; "
                        + "duplicate chain key 'c'; chain 'c': name is required; chain 'c': entries must not be empty; "
                        + "chain 'd' references unknown workflow key 'ghost'; "
                        + "chain 'e' references unknown workflow key 'null'");
    }

    @Test
    void reportsScheduleProblems() {
        List<BundleSchedule> schedules = Arrays.asList(
                null,
                schedule(null, null, "0 0 6 * * *", null),
                schedule(new BundleTarget("w", "c"), null, "0 0 6 * * *", null),
                schedule(new BundleTarget(null, null), null, "0 0 6 * * *", null),
                schedule(new BundleTarget("ghost", null), null, "0 0 6 * * *", null),
                schedule(new BundleTarget(null, "nochain"), null, "0 0 6 * * *", null),
                schedule(new BundleTarget("w", null), "weekly", null, null),
                schedule(new BundleTarget("w", null), null, null, null),
                schedule(new BundleTarget("w", null), "cron", "every day", null),
                schedule(new BundleTarget("w", null), "DELAYED", null, "queue"));

        assertThatThrownBy(() -> BundleValidator.validate(bundle(PROJECT, List.of(workflow("w", "W", null, null)),
                List.of(), schedules, List.of())))
                .hasMessage("Invalid bundle: "
                        + "schedules[0]: target needs exactly one of workflowKey and chainKey; "
                        + "schedules[1]: target needs exactly one of workflowKey and chainKey; "
                        + "schedules[2]: target needs exactly one of workflowKey and chainKey; "
                        + "schedules[3]: target needs exactly one of workflowKey and chainKey; "
                        + "schedules[4] references unknown workflow key 'ghost'; "
                        + "schedules[5] references unknown chain key 'nochain'; "
                        + "schedules[6]: scheduleType 'weekly' is unknown; "
                        + "schedules[7]: invalid cron expression 'null'; "
                        + "schedules[8]: invalid cron expression 'every day'; "
                        + "schedules[9]: conflictBehavior 'queue' is unknown");
    }

    @Test
    void reportsMalformedDuplicateAndUnlistedPathTokens() {
        List<BundlePath> paths = Arrays.asList(
                null,
                new BundlePath(null, "/a", 1, "h"),
                new BundlePath("@@path:x@@", "/b", 1, "h"),
                new BundlePath("@@path:1@@", "/c", 1, "h"),
                new BundlePath("@@path:1@@", "/d", 1, "h"));
        List<BundleWorkflow> workflows = List.of(workflow("w", "W", "@@path:3@@ @@path:2@@ @@path:1@@ @@path:2@@",
                null));

        assertThatThrownBy(() -> BundleValidator.validate(bundle(PROJECT, workflows, List.of(), List.of(), paths)))
                .hasMessage("Invalid bundle: paths: invalid token 'null'; paths: invalid token 'null'; "
                        + "paths: invalid token '@@path:x@@'; paths: duplicate token '@@path:1@@'; "
                        + "path token @@path:2@@ is used but not listed in paths; "
                        + "path token @@path:3@@ is used but not listed in paths");
    }

    @Test
    void findsPathTokensInChainAndScheduleContextsToo() {
        BundleChain c = new BundleChain("c", "C", null, null, null,
                List.of(new BundleChainEntry("w", null, 0, null, Map.of("dir", "@@path:4@@"), null)));
        BundleSchedule s = new BundleSchedule(new BundleTarget("w", null), "0 0 6 * * *", null, null, null, null,
                Map.of("dir", "@@path:5@@/x"), null, null, null, null);

        assertThatThrownBy(() -> BundleValidator.validate(bundle(PROJECT, List.of(workflow("w", "W", null, null)),
                List.of(c), List.of(s), List.of())))
                .hasMessageContaining("path token @@path:4@@ is used")
                .hasMessageContaining("path token @@path:5@@ is used");
    }

    @Test
    void showsAtMostFifteenProblemsAndTheTotal() {
        List<BundleWorkflow> workflows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            workflows.add(new BundleWorkflow("", null));
        }

        assertThatThrownBy(() -> BundleValidator.validate(bundle(PROJECT, workflows, List.of(), List.of(), List.of())))
                .hasMessageContaining("workflows[14].key is required; … (20 problems)")
                .hasMessageNotContaining("workflows[15]");
    }
}

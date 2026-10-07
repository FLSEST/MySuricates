package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleChainEntry;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundleProject;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BundleWarningsTest {

    private static BundleWorkflow workflow(String key, List<Map<String, Object>> blocks, List<String> activated) {
        return new BundleWorkflow(key, new BundleDefinition(key.toUpperCase(), null, null, blocks, null, null, null,
                null, null, null, null, activated));
    }

    private static ProjectBundle bundle(List<BundleWorkflow> workflows, List<BundleChain> chains,
                                        List<String> skillNames, List<String> warnings) {
        return new ProjectBundle(ProjectBundle.FORMAT, ProjectBundle.FORMAT_VERSION, null, null,
                new BundleProject("p", null, null, null, null, null), workflows, chains, List.of(), List.of(),
                skillNames, warnings);
    }

    private static final BundleChain CHAIN = new BundleChain("c", "C", null, null, null,
            List.of(new BundleChainEntry("a", null, 0, null, null, null)));

    @Test
    void referencedSkills_collectsNamesActivatedSkillsAndNestedSkillRefs() {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "simple", "config", Map.of("skillIds", List.of("@@skill:git-tools@@"))),
                Map.of("type", "foreach", "config", Map.of("blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("skillIds", List.of("@@skill:deep one@@")))))),
                Map.of("type", "shell", "config", Map.of("command", "echo @@skill:not-whole@@ here")));
        ProjectBundle b = bundle(List.of(workflow("a", blocks, List.of("writer")), workflow("b", null, null)),
                List.of(), List.of("listed"), List.of());

        assertThat(BundleWarnings.referencedSkills(b))
                .containsExactly("deep one", "git-tools", "listed", "writer");
    }

    @Test
    void of_warnsAboutExternalSubWorkflowsMissingSkillsAndKeepsBundleWarnings() {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "ext-uuid")),
                Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "@@workflow:b@@")),
                Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "{{ target }}")));
        ProjectBundle b = bundle(List.of(workflow("a", blocks, List.of("known", "unknown"))), List.of(),
                List.of(), List.of("from export"));

        List<String> warnings = BundleWarnings.of(b, Set.of("known"), true);

        assertThat(warnings).containsExactly(
                BundleBlocks.externalRefWarning("A", "ext-uuid"),
                "from export",
                BundleWarnings.missingSkill("unknown"));
    }

    @Test
    void of_reportsEachWarningOnlyOnce() {
        String missing = BundleWarnings.missingSkill("gone");
        ProjectBundle b = bundle(List.of(workflow("a", List.of(), List.of("gone"))), List.of(), List.of("gone"),
                List.of(missing, missing));

        assertThat(BundleWarnings.of(b, Set.of(), true)).containsExactly(missing);
    }

    @Test
    void of_warnsAboutChainsOnlyWhenTheBundleHasChainsAndTheModuleIsOff() {
        ProjectBundle withChains = bundle(List.of(workflow("a", List.of(), null)), List.of(CHAIN), List.of(), List.of());
        ProjectBundle withoutChains = bundle(List.of(workflow("a", List.of(), null)), List.of(), List.of(), List.of());

        assertThat(BundleWarnings.of(withChains, Set.of(), false)).containsExactly(BundleWarnings.CHAINS_OFF);
        assertThat(BundleWarnings.of(withChains, Set.of(), true)).isEmpty();
        assertThat(BundleWarnings.of(withoutChains, Set.of(), false)).isEmpty();
    }

    @Test
    void missingSkill_namesTheSkill() {
        assertThat(BundleWarnings.missingSkill("git-tools")).contains("'git-tools'").contains("without it");
    }
}

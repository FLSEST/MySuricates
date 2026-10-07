package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundlePath;
import com.automation.dto.bundle.ProjectBundle.BundleProject;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundlePathMappingTest {

    private static final BundlePath P1 = new BundlePath("@@path:1@@", "/home/u/shop", 2, "Tests › instructions");
    private static final BundlePath P2 = new BundlePath("@@path:2@@", "/data/out", 1, "Tests › blocks[0]");

    private static ProjectBundle bundle(List<BundlePath> paths, String instructions, String command) {
        List<Map<String, Object>> blocks = List.of(Map.of("type", "shell", "config", Map.of("command", command)));
        BundleDefinition d = new BundleDefinition("Tests @@path:1@@", "desc @@path:1@@", null, blocks, instructions,
                null, null, null, null, null, null, null);
        return new ProjectBundle(ProjectBundle.FORMAT, ProjectBundle.FORMAT_VERSION, null, null,
                new BundleProject("p", null, null, null, null, null), List.of(new BundleWorkflow("tests", d)),
                List.of(), List.of(), paths, List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static String command(ProjectBundle b) {
        return (String) ((Map<String, Object>) b.workflows().get(0).definition().blocks().get(0).get("config"))
                .get("command");
    }

    @Test
    void replacesEveryTokenWithItsTargetIncludingSubPaths() {
        ProjectBundle b = bundle(List.of(P1, P2), "cd @@path:1@@/tests && ls @@path:2@@",
                "cp @@path:1@@/a @@path:2@@/b");

        ProjectBundle mapped = BundlePathMapping.apply(b, Map.of("@@path:1@@", "/new/root", "@@path:2@@", "/srv/out"));

        assertThat(mapped.workflows().get(0).definition().instructions()).isEqualTo("cd /new/root/tests && ls /srv/out");
        assertThat(command(mapped)).isEqualTo("cp /new/root/a /srv/out/b");
        assertThat(mapped.paths()).containsExactly(P1, P2);
    }

    @Test
    void namesAndDescriptionsAreNoContentAndStayUntouched() {
        ProjectBundle mapped = BundlePathMapping.apply(bundle(List.of(P1), "x", "y"), Map.of("@@path:1@@", "/n"));

        assertThat(mapped.workflows().get(0).definition().name()).isEqualTo("Tests @@path:1@@");
        assertThat(mapped.workflows().get(0).definition().description()).isEqualTo("desc @@path:1@@");
    }

    @Test
    void stripsWhitespaceAndTrailingSlashesButKeepsTheRoot() {
        ProjectBundle b = bundle(List.of(P1, P2), "@@path:1@@/tests", "@@path:2@@/x");

        ProjectBundle mapped = BundlePathMapping.apply(b, Map.of("@@path:1@@", "  /new/root//  ", "@@path:2@@", "/"));

        assertThat(mapped.workflows().get(0).definition().instructions()).isEqualTo("/new/root/tests");
        assertThat(command(mapped)).isEqualTo("//x");
    }

    @Test
    void targetsAreInsertedLiterallyEvenWithRegexReplacementCharacters() {
        ProjectBundle mapped = BundlePathMapping.apply(bundle(List.of(P1), "@@path:1@@", "x"),
                Map.of("@@path:1@@", "/tmp/$1/a\\b"));

        assertThat(mapped.workflows().get(0).definition().instructions()).isEqualTo("/tmp/$1/a\\b");
    }

    @Test
    void leavesTheProjectPathPlaceholderAndUnlistedTokensAlone() {
        ProjectBundle mapped = BundlePathMapping.apply(bundle(List.of(P1), "{{ project_path }} @@path:7@@ @@path:1@@", "x"),
                Map.of("@@path:1@@", "/n"));

        assertThat(mapped.workflows().get(0).definition().instructions()).isEqualTo("{{ project_path }} @@path:7@@ /n");
    }

    @Test
    void listsEveryTokenWithoutANonBlankTarget() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("@@path:2@@", "  ");

        assertThatThrownBy(() -> BundlePathMapping.apply(bundle(List.of(P1, P2), "x", "y"), mappings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing path mapping for: @@path:1@@ (/home/u/shop), @@path:2@@ (/data/out)");
    }

    @Test
    void aBundleWithoutPathsIsReturnedAsIs() {
        ProjectBundle b = bundle(List.of(), "@@path:1@@", "y");

        assertThat(BundlePathMapping.apply(b, Map.of("@@path:1@@", "/ignored"))).isSameAs(b);
    }
}

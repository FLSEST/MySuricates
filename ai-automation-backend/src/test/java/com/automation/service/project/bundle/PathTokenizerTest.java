package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle.BundlePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathTokenizerTest {

    /** Runs both passes over the texts and returns the replaced texts. */
    private static List<String> tokenize(PathTokenizer t, String... texts) {
        List<String> placeholders = java.util.Arrays.stream(texts).map(t::withProjectPlaceholder).toList();
        placeholders.forEach(s -> t.collect(s, "hint"));
        t.finish();
        return placeholders.stream().map(t::replace).toList();
    }

    @Test
    void projectPathBecomesPlaceholder_otherPathsBecomeTokens() {
        PathTokenizer t = new PathTokenizer("/home/u/code/shopapp/");
        List<String> out = tokenize(t, "cd /home/u/code/shopapp && pytest /home/u/code/shopapp/tests; ls /tmp/shopapp");

        assertThat(out).containsExactly("cd {{ project_path }} && pytest {{ project_path }}/tests; ls @@path:1@@");
    }

    @Test
    void subPathOfACollectedPathIsWrittenAsTokenPlusRest() {
        PathTokenizer t = new PathTokenizer(null);
        List<String> out = tokenize(t, "rm -rf /tmp/shopapp", "pytest /tmp/shopapp/tests/.");
        List<BundlePath> paths = t.finish();

        assertThat(out).containsExactly("rm -rf @@path:1@@", "pytest @@path:1@@/tests/.");
        assertThat(paths).singleElement().satisfies(p -> {
            assertThat(p.token()).isEqualTo("@@path:1@@");
            assertThat(p.original()).isEqualTo("/tmp/shopapp");
            assertThat(p.occurrences()).isEqualTo(2);
        });
    }

    @Test
    void shortestRootWinsEvenWhenTheLongerPathAppearsFirst() {
        PathTokenizer t = new PathTokenizer(null);
        List<String> out = tokenize(t, "cat /tmp/a/b/c.txt", "ls /tmp/a/b", "ls /tmp/a");

        assertThat(out).containsExactly("cat @@path:1@@/b/c.txt", "ls @@path:1@@/b", "ls @@path:1@@");
        assertThat(t.finish()).extracting(BundlePath::original).containsExactly("/tmp/a");
    }

    @Test
    void boundaryIsTheSlash_venvIsNoSubPathOfTheProjectDir() {
        PathTokenizer t = new PathTokenizer("/tmp/shopapp");
        List<String> out = tokenize(t, "cd /tmp/shopapp && /tmp/shopapp-qa-venv/bin/python -m pytest");

        assertThat(out).containsExactly("cd {{ project_path }} && @@path:1@@ -m pytest");
        assertThat(t.finish()).extracting(BundlePath::original).containsExactly("/tmp/shopapp-qa-venv/bin/python");
    }

    @Test
    void siblingPrefixWithoutSlashIsASeparateToken() {
        PathTokenizer t = new PathTokenizer(null);
        List<String> out = tokenize(t, "/tmp/shopapp /tmp/shopapp-qa-venv");

        assertThat(out).containsExactly("@@path:1@@ @@path:2@@");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '#', quoteCharacter = '§', value = {
            "echo \"/tmp/x\"# echo \"@@path:1@@\"",
            "echo '/tmp/x'# echo '@@path:1@@'",
            "cd /tmp/x; ls# cd @@path:1@@; ls",
            "(cd /tmp/x)# (cd @@path:1@@)",
            "cat /tmp/x| wc# cat @@path:1@@| wc",
            "ls /tmp/x# ls @@path:1@@",
            "Ergebnis liegt in /tmp/x.# Ergebnis liegt in @@path:1@@.",
    })
    void pathEndsAtQuotesSemicolonParenPipeAndLineEnd(String in, String expected) {
        PathTokenizer t = new PathTokenizer(null);
        assertThat(tokenize(t, in)).containsExactly(expected.strip());
        assertThat(t.finish()).extracting(BundlePath::original).containsExactly("/tmp/x");
    }

    @Test
    void pathEndsAtLineEndInMultiLineText() {
        PathTokenizer t = new PathTokenizer(null);
        assertThat(tokenize(t, "cd /home/u/data\nls /home/u/data/out\n"))
                .containsExactly("cd @@path:1@@\nls @@path:1@@/out\n");
    }

    @Test
    void onlyKnownRootsAndHomeCount_andNotInsideOtherWords() {
        PathTokenizer t = new PathTokenizer(null);
        List<String> out = tokenize(t, "ls /etc/passwd ~/notes foo/tmp/x https://h/tmp/y {{ base }}/tmp/z /usr/bin/env");

        assertThat(out).containsExactly("ls /etc/passwd @@path:1@@ foo/tmp/x https://h/tmp/y {{ base }}/tmp/z /usr/bin/env");
        assertThat(t.finish()).extracting(BundlePath::original).containsExactly("~/notes");
    }

    @Test
    void wholeValueIsTokenized_evenOutsideTheKnownRoots_andJoinsTextPaths() {
        PathTokenizer t = new PathTokenizer("/home/u/.mysuricates/sources/shopapp");
        t.collectWhole("/home/u/.mysuricates/sources/shopapp", "source_path");
        t.collectWhole("/workspace/other", "source_path");
        t.collectWhole("relative/dir", "source_path");
        String text = t.withProjectPlaceholder("cat /home/u/.mysuricates/sources/shopapp/README");
        t.collect(text, "cmd");
        List<BundlePath> paths = t.finish();

        assertThat(text).isEqualTo("cat {{ project_path }}/README");
        assertThat(t.replaceWhole("/home/u/.mysuricates/sources/shopapp")).isEqualTo("@@path:1@@");
        assertThat(t.replaceWhole("/workspace/other")).isEqualTo("@@path:2@@");
        assertThat(t.replaceWhole("relative/dir")).isEqualTo("relative/dir");
        assertThat(paths).extracting(BundlePath::original)
                .containsExactly("/home/u/.mysuricates/sources/shopapp", "/workspace/other");
        assertThat(paths.get(0).hint()).isEqualTo("source_path");
    }

    @Test
    void projectPathOutsideTheKnownRootsIsCollectedWhereNoPlaceholderIsWanted() {
        PathTokenizer t = new PathTokenizer("/workspace/shopapp");
        t.collect("/workspace/shopapp/out", "ctx");
        t.collect("/workspace/shopapp-other /workspace/shopapp.bak", "ctx");
        List<BundlePath> paths = t.finish();

        assertThat(paths).extracting(BundlePath::original).containsExactly("/workspace/shopapp/out");
        assertThat(t.replace("dir=/workspace/shopapp/out/x")).isEqualTo("dir=@@path:1@@/x");
        assertThat(t.replace("/workspace/shopapp-other")).isEqualTo("/workspace/shopapp-other");
    }

    @Test
    void singleSegmentProjectPathIsNoPlaceholder() {
        PathTokenizer t = new PathTokenizer("/tmp");
        assertThat(t.withProjectPlaceholder("ls /tmp/a")).isEqualTo("ls /tmp/a");
    }

    @Test
    void projectPathNeedsABoundaryOnBothSides() {
        PathTokenizer t = new PathTokenizer("/home/u/p");
        assertThat(t.withProjectPlaceholder("/home/u/p.bak /home/u/px x/home/u/p /home/u/p."))
                .isEqualTo("/home/u/p.bak /home/u/px x/home/u/p {{ project_path }}.");
    }

    @Test
    void trailingSlashIsPartOfTheRest() {
        PathTokenizer t = new PathTokenizer(null);
        assertThat(tokenize(t, "cp a /tmp/out/", "ls /tmp/out")).containsExactly("cp a @@path:1@@/", "ls @@path:1@@");
    }

    @Test
    void tokensAreNumberedInOrderOfFirstAppearance() {
        PathTokenizer t = new PathTokenizer(null);
        tokenize(t, "/tmp/zzz-long-name", "/tmp/a");
        assertThat(t.finish()).extracting(BundlePath::original).containsExactly("/tmp/zzz-long-name", "/tmp/a");
    }

    @Test
    void replaceBeforeFinishIsAProgrammingError() {
        PathTokenizer t = new PathTokenizer(null);
        assertThatThrownBy(() -> t.replace("/tmp/x")).isInstanceOf(IllegalStateException.class);
    }
}

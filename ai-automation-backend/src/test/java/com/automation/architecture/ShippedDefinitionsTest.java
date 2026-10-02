package com.automation.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool gates read only what is shipped (TODO ca858a23): a private pool file, ignored via
 * {@code .git/info/exclude}, made the gates red on the maintainer's checkout and green in a fresh
 * worktree.
 */
class ShippedDefinitionsTest {

    @TempDir
    Path tmp;

    @Test
    void poolFiles_inARepository_areTheTrackedOnes() throws Exception {
        Path pool = Files.createDirectories(tmp.resolve("workflow-pool"));
        git(tmp, "init", "-q");
        Files.writeString(pool.resolve("shipped.json"), "{}");
        Files.writeString(pool.resolve("staged-new.json"), "{}");
        Files.writeString(pool.resolve("private.json"), "{}");
        Files.writeString(pool.resolve("untracked.json"), "{}");
        Files.writeString(pool.resolve("README.md"), "# Pool");
        Files.createDirectories(pool.resolve("nested"));
        Files.writeString(pool.resolve("nested/deep.json"), "{}");
        Files.writeString(tmp.resolve(".git/info/exclude"), "workflow-pool/private.json\n");
        git(tmp, "add", "workflow-pool/shipped.json", "workflow-pool/staged-new.json",
                "workflow-pool/README.md", "workflow-pool/nested/deep.json");

        List<Path> files = ShippedDefinitions.poolFiles(pool);

        assertThat(files).extracting(p -> p.getFileName().toString())
                .containsExactly("shipped.json", "staged-new.json");
    }

    @Test
    void poolFiles_whenGitCannotAnswer_areAllJsonFilesOfTheDirectory() throws IOException {
        Path pool = Files.createDirectories(tmp.resolve("archive/workflow-pool"));
        Files.writeString(pool.resolve("b.json"), "{}");
        Files.writeString(pool.resolve("a.json"), "{}");
        Files.writeString(pool.resolve("notes.txt"), "x");
        // A .git file pointing nowhere: git fails here no matter which repository encloses the
        // temp dir — the same answer it gives in an unpacked source archive.
        Files.writeString(tmp.resolve("archive/.git"), "gitdir: " + tmp.resolve("missing") + "\n");

        List<Path> files = ShippedDefinitions.poolFiles(pool);

        assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("a.json", "b.json");
    }

    @Test
    void poolFiles_whenGitTracksNoJsonThere_fallBackToAllJsonFiles() throws Exception {
        // git answers with exit 0 and an empty list (repository, but nothing tracked in the pool):
        // an empty result would let every gate pass without reading anything.
        Path pool = Files.createDirectories(tmp.resolve("workflow-pool"));
        git(tmp, "init", "-q");
        Files.writeString(pool.resolve("only-local.json"), "{}");

        assertThat(ShippedDefinitions.poolFiles(pool)).extracting(p -> p.getFileName().toString())
                .containsExactly("only-local.json");
    }

    @Test
    void poolFiles_ofThisRepository_leaveIgnoredFilesOut() throws Exception {
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        assertThat(pool).as("workflow-pool reachable from the backend module").isNotNull();
        Process ignored = new ProcessBuilder("git", "ls-files", "--others", "--ignored", "--exclude-standard", "--", ".")
                .directory(pool.toFile()).redirectErrorStream(true).start();
        List<String> ignoredNames = new String(ignored.getInputStream().readAllBytes()).lines()
                .filter(name -> name.endsWith(".json")).toList();
        ignored.waitFor();

        // Usually empty (a fresh worktree has no private files) — then this only checks non-empty.
        assertThat(ShippedDefinitions.poolFiles(pool)).isNotEmpty()
                .extracting(p -> p.getFileName().toString())
                .noneMatch(ignoredNames::contains);
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("git " + String.join(" ", args) + ": " + out).isZero();
    }
}

package com.automation.util;

import com.automation.util.UnattendedContainerCommand.Bind;
import com.automation.util.UnattendedContainerCommand.CliAccess;
import com.automation.util.UnattendedContainerCommand.Plan;
import com.automation.util.UnattendedContainerCommand.Setup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// TODO 6ee57b87: unattended AI CLIs and shell blocks run in a container that holds no secret of the instance
class UnattendedContainerCommandTest {

    @TempDir
    Path tmp;

    private Setup setup(List<Path> hidden, List<Path> dataDirectories, Map<String, CliAccess> clis) {
        return new Setup("automation-agent-workflow:latest", 1000, 1001, hidden, tmp.resolve("home/u"), dataDirectories,
                List.of(), "", "", 4096, 42L, clis, List.of(), Map.of());
    }

    private Setup plain() {
        return setup(List.of(), List.of(), Map.of());
    }

    private static Plan build(Setup setup, Path workdir, String... command) {
        return UnattendedContainerCommand.build(setup, List.of(command), workdir, List.of(), "mysuricates-unattended-x");
    }

    /** The values of every {@code --mount} argument. */
    private static List<String> mounts(Plan plan) {
        List<String> mounts = new ArrayList<>();
        for (int i = 0; i < plan.command().size() - 1; i++) {
            if (plan.command().get(i).equals("--mount")) {
                mounts.add(plan.command().get(i + 1));
            }
        }
        return mounts;
    }

    private Path project() throws IOException {
        return Files.createDirectories(tmp.resolve("projects/demo"));
    }

    @Nested
    @DisplayName("the container itself")
    class Container {

        @Test
        @DisplayName("starts the shell block in a fresh, locked-down container under the host user, image last before the command")
        void lockedDown() throws IOException {
            Path workdir = project();

            Plan plan = build(plain(), workdir, "/bin/bash", "-c", "echo hi");

            assertThat(plan.command()).startsWith("docker", "run", "--rm", "-i", "--init", "--pull", "never",
                    "--name", "mysuricates-unattended-x");
            assertThat(String.join(" ", plan.command()))
                    .contains("--label org.mysuricates.unattended=true", "--label org.mysuricates.owner-pid=42",
                            "--user 1000:1001", "--cap-drop ALL", "--security-opt no-new-privileges", "--pids-limit 4096",
                            "--tmpfs /home/agent:rw,exec,mode=0700,uid=1000,gid=1001", "-e HOME=/home/agent",
                            "--workdir " + workdir)
                    .doesNotContain("docker.sock", "--privileged", "--network host");
            assertThat(plan.command().subList(plan.command().size() - 4, plan.command().size()))
                    .containsExactly("automation-agent-workflow:latest", "/bin/bash", "-c", "echo hi");
        }

        @Test
        @DisplayName("mounts the working directory read-write under its own path, so paths in prompts stay valid")
        void workdirSamePath() throws IOException {
            Path workdir = project();

            assertThat(mounts(build(plain(), workdir, "/bin/bash", "-c", "true")))
                    .contains("type=bind,src=" + workdir + ",dst=" + workdir);
        }

        @Test
        @DisplayName("without a working directory nothing of the host is mounted")
        void noWorkdir() {
            Plan plan = build(plain(), null, "/bin/bash", "-c", "true");

            assertThat(mounts(plan)).isEmpty();
            assertThat(plan.command()).doesNotContain("--workdir");
        }

        @Test
        @DisplayName("a cache volume holds Maven, npm and pip downloads; memory is limited only when configured")
        void cacheAndMemory() {
            Setup setup = new Setup("img", 1000, 1000, List.of(), tmp.resolve("home/u"), List.of(), List.of(), "cache-vol",
                    "6g", 100, 1L, Map.of(), List.of(), Map.of("TZ", "Europe/Berlin"));

            Plan plan = build(setup, null, "/bin/bash", "-c", "true");

            assertThat(mounts(plan)).containsExactly("type=volume,src=cache-vol,dst=/cache");
            assertThat(String.join(" ", plan.command())).contains("--memory 6g",
                    "-e MAVEN_OPTS=-Dmaven.repo.local=/cache/m2", "-e npm_config_cache=/cache/npm", "-e TZ=Europe/Berlin");
            assertThat(String.join(" ", build(plain(), null, "x").command())).doesNotContain("--memory", "/cache");
        }

        @Test
        @DisplayName("a directory the caller names as writable is mounted under its own path")
        void writable() throws IOException {
            Path answers = Files.createDirectories(tmp.resolve("codex-result-1"));

            Plan plan = UnattendedContainerCommand.build(plain(), List.of("x"), null, List.of(answers), "n");

            assertThat(mounts(plan)).contains("type=bind,src=" + answers + ",dst=" + answers);
        }

        @Test
        @DisplayName("a path --mount would split is refused instead of mis-mounted")
        void commaRefused() throws IOException {
            Path odd = Files.createDirectories(tmp.resolve("a,b"));

            assertThatThrownBy(() -> build(plain(), odd, "x"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("comma");
        }
    }

    @Nested
    @DisplayName("secrets inside a mounted directory")
    class Secrets {

        @Test
        @DisplayName("the database is covered with its whole folder, so journal files and backups appearing later are too")
        void databaseFolder() throws IOException {
            Path workdir = project();
            Path db = Files.createDirectories(workdir.resolve("backend/data")).resolve("automation.db");
            Files.writeString(db, "dummy");

            Plan plan = build(setup(List.of(db, Path.of(db + "-wal")), List.of(), Map.of()), workdir, "x");

            assertThat(mounts(plan)).contains("type=tmpfs,dst=" + workdir.resolve("backend/data"));
        }

        @Test
        @DisplayName("a key file is covered by an empty read-only file; one that does not exist needs no mask")
        void keyFile() throws IOException {
            Path workdir = project();
            Path key = Files.writeString(workdir.resolve("seal-signing.key"), "dummy");

            Plan plan = build(setup(List.of(key, workdir.resolve("api-key")), List.of(), Map.of()), workdir, "x");

            assertThat(mounts(plan)).contains("type=bind,src=/dev/null,dst=" + key + ",readonly")
                    .noneMatch(m -> m.contains("api-key"));
        }

        @Test
        @DisplayName("a database right in the working directory is covered as a file, the directory stays usable")
        void databaseInRoot() throws IOException {
            Path workdir = project();
            Path db = Files.writeString(workdir.resolve("automation.db"), "dummy");

            assertThat(mounts(build(setup(List.of(db), List.of(), Map.of()), workdir, "x")))
                    .contains("type=bind,src=/dev/null,dst=" + db + ",readonly")
                    .doesNotContain("type=tmpfs,dst=" + workdir);
        }

        @Test
        @DisplayName("a hidden directory and a data directory inside the working directory become empty")
        void directories() throws IOException {
            Path workdir = project();
            Path backups = Files.createDirectories(workdir.resolve("backups"));
            Path dataHome = Files.createDirectories(workdir.resolve(".mysuricates"));
            Files.writeString(dataHome.resolve("api-key"), "dummy");

            List<String> mounts = mounts(build(setup(List.of(backups, dataHome.resolve("api-key")), List.of(dataHome),
                    Map.of()), workdir, "x"));

            assertThat(mounts).contains("type=tmpfs,dst=" + backups, "type=tmpfs,dst=" + dataHome)
                    .as("no extra mask inside a folder that is empty anyway").noneMatch(m -> m.contains("api-key"));
        }

        @Test
        @DisplayName("a working directory reached through a symlink still gets its database covered")
        void symlinkedWorkdir() throws IOException {
            Path real = project();
            Path db = Files.createDirectories(real.resolve("data")).resolve("automation.db");
            Files.writeString(db, "dummy");
            Path link = Files.createSymbolicLink(tmp.resolve("link"), real);

            assertThat(mounts(build(setup(List.of(db), List.of(), Map.of()), link, "x")))
                    .contains("type=tmpfs,dst=" + link.resolve("data"));
        }

        @Test
        @DisplayName("the backend's startup configuration is read-only where the working directory exposes it")
        void startupConfiguration() throws IOException {
            Path workdir = project();
            Path config = Files.createDirectories(workdir.resolve("backend/config"));
            Setup setup = new Setup("img", 1000, 1000, List.of(), tmp.resolve("home/u"), List.of(),
                    List.of(config, workdir.resolve("backend/application.yml")), "", "", 64, 1L, Map.of(), List.of(), Map.of());

            assertThat(mounts(build(setup, workdir, "x")))
                    .contains("type=bind,src=" + config + ",dst=" + config + ",readonly",
                            "type=bind,src=" + workdir.resolve("backend") + ",dst=" + workdir.resolve("backend"))
                    .as("a missing file needs no bind").noneMatch(m -> m.contains("application.yml"));
        }

        @Test
        @DisplayName("the home directory, a directory containing it, a data directory itself and the root are refused")
        void protectedRoots() throws IOException {
            Path home = Files.createDirectories(tmp.resolve("home/u"));
            Path dataHome = Files.createDirectories(home.resolve(".mysuricates"));
            Setup setup = setup(List.of(), List.of(dataHome), Map.of());

            assertThatThrownBy(() -> build(setup, home, "x")).hasMessageContaining("home directory");
            assertThatThrownBy(() -> build(setup, tmp.resolve("home"), "x")).hasMessageContaining("home directory");
            assertThatThrownBy(() -> build(setup, Path.of("/"), "x")).hasMessageContaining("refuses to mount");
            assertThatThrownBy(() -> build(setup, dataHome, "x")).hasMessageContaining("data directory");
        }

        @Test
        @DisplayName("a run workspace inside a data directory is fine, a directory inside a hidden one is not")
        void insideDataDirectory() throws IOException {
            Path dataHome = Files.createDirectories(tmp.resolve("home/u/.mysuricates"));
            Path workspace = Files.createDirectories(dataHome.resolve("workspaces/p/r"));
            Path backups = Files.createDirectories(dataHome.resolve("backups"));
            Path inBackups = Files.createDirectories(backups.resolve("dev"));
            Setup setup = setup(List.of(backups), List.of(dataHome), Map.of());

            assertThat(mounts(build(setup, workspace, "x"))).contains("type=bind,src=" + workspace + ",dst=" + workspace);
            assertThatThrownBy(() -> build(setup, inBackups, "x")).hasMessageContaining("secrets");
        }
    }

    @Nested
    @DisplayName("git")
    class Git {

        @Test
        @DisplayName("config and hooks of the repository are read-only: both run commands on the host later")
        void repository() throws IOException {
            Path workdir = project();
            Path git = Files.createDirectories(workdir.resolve(".git/hooks")).getParent();
            Files.writeString(git.resolve("config"), "[core]\n");

            assertThat(mounts(build(plain(), workdir, "x"))).contains(
                    "type=bind,src=" + git.resolve("config") + ",dst=" + git.resolve("config") + ",readonly",
                    "type=bind,src=" + git.resolve("hooks") + ",dst=" + git.resolve("hooks") + ",readonly");
        }

        /** A repository with one worktree, as git lays it out; returns {common, gitDir, workdir}. */
        private Path[] worktreeLayout() throws IOException {
            Path common = Files.createDirectories(tmp.resolve("repo/.git"));
            Files.createDirectories(common.resolve("hooks"));
            Files.createDirectories(common.resolve("objects"));
            Files.createDirectories(common.resolve("refs"));
            Files.writeString(common.resolve("HEAD"), "ref: refs/heads/main\n");
            Files.writeString(common.resolve("config"), "[core]\n");
            Path gitDir = Files.createDirectories(common.resolve("worktrees/wt"));
            Files.writeString(gitDir.resolve("commondir"), "../..\n");
            Path workdir = project();
            Files.writeString(gitDir.resolve("gitdir"), workdir.resolve(".git") + "\n");
            Files.writeString(workdir.resolve(".git"), "gitdir: " + gitDir + "\n");
            return new Path[] {common, gitDir, workdir};
        }

        @Test
        @DisplayName("a worktree also gets its repository's git directory, with config, hooks and the pointer files read-only")
        void worktree() throws IOException {
            Path[] layout = worktreeLayout();
            Path common = layout[0], gitDir = layout[1], workdir = layout[2];

            List<String> mounts = mounts(build(plain(), workdir, "x"));

            assertThat(mounts).contains("type=bind,src=" + common + ",dst=" + common,
                    "type=bind,src=" + common.resolve("config") + ",dst=" + common.resolve("config") + ",readonly",
                    "type=bind,src=" + common.resolve("hooks") + ",dst=" + common.resolve("hooks") + ",readonly",
                    "type=bind,src=" + workdir.resolve(".git") + ",dst=" + workdir.resolve(".git") + ",readonly",
                    "type=bind,src=" + gitDir.resolve("gitdir") + ",dst=" + gitDir.resolve("gitdir") + ",readonly",
                    "type=bind,src=" + gitDir.resolve("commondir") + ",dst=" + gitDir.resolve("commondir") + ",readonly");
        }

        @Test
        @DisplayName("the directories on the way to a read-only file are mountpoints, so none can be renamed away")
        void ancestorsPinned() throws IOException {
            Path[] layout = worktreeLayout();
            Path common = layout[0], gitDir = layout[1], workdir = layout[2];
            Path repo = Files.createDirectories(tmp.resolve("projects/repo"));
            Files.createDirectories(repo.resolve(".git/hooks"));

            assertThat(mounts(build(plain(), workdir, "x"))).contains(
                    "type=bind,src=" + common.resolve("worktrees") + ",dst=" + common.resolve("worktrees"),
                    "type=bind,src=" + gitDir + ",dst=" + gitDir);
            assertThat(mounts(build(plain(), repo, "x"))).contains(
                    "type=bind,src=" + repo.resolve(".git") + ",dst=" + repo.resolve(".git"));
        }

        @Test
        @DisplayName("a .git pointer without git's own back-pointer mounts nothing beyond the working directory")
        void forgedPointer() throws IOException {
            Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere/.git"));
            for (String n : List.of("objects", "refs")) {
                Files.createDirectories(elsewhere.resolve(n));
            }
            Files.writeString(elsewhere.resolve("HEAD"), "ref: refs/heads/main\n");
            Path gitDir = Files.createDirectories(elsewhere.resolve("worktrees/wt"));
            Files.writeString(gitDir.resolve("commondir"), "../..\n");
            Path workdir = project();
            Files.writeString(workdir.resolve(".git"), "gitdir: " + gitDir + "\n");

            List<String> mounts = mounts(build(plain(), workdir, "x"));

            assertThat(mounts).noneMatch(m -> m.contains("elsewhere"));
            Files.writeString(gitDir.resolve("gitdir"), tmp.resolve("other/.git") + "\n");
            assertThat(mounts(build(plain(), workdir, "x"))).as("a back-pointer to another place").noneMatch(m -> m.contains("elsewhere"));
            Files.writeString(gitDir.resolve("gitdir"), workdir.resolve(".git") + "\n");
            assertThat(mounts(build(plain(), workdir, "x"))).as("the real layout").anyMatch(m -> m.contains("elsewhere"));
        }
    }

    @Nested
    @DisplayName("AI CLIs")
    class Clis {

        @Test
        @DisplayName("a single binary goes in read-only and replaces the command's program; secrets only by name")
        void binary() throws IOException {
            Path claude = Files.writeString(Files.createDirectories(tmp.resolve("share/claude/versions")).resolve("2.1"), "elf");
            CliAccess access = new CliAccess(claude, List.of(), List.of(),
                    Map.of("CLAUDE_CODE_OAUTH_TOKEN", "dummy-token-value", "CLAUDE_CONFIG_DIR", "/home/agent/.claude"));

            Plan plan = build(setup(List.of(), List.of(), Map.of("claude", access)), null, "claude", "-p", "--verbose");

            assertThat(mounts(plan)).contains("type=bind,src=" + claude + ",dst=/opt/host-cli/claude,readonly");
            assertThat(plan.command().subList(plan.command().size() - 3, plan.command().size()))
                    .containsExactly("/opt/host-cli/claude", "-p", "--verbose");
            assertThat(String.join(" ", plan.command())).contains("-e CLAUDE_CODE_OAUTH_TOKEN")
                    .doesNotContain("dummy-token-value");
            assertThat(plan.clientEnvironment()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "dummy-token-value");
        }

        @Test
        @DisplayName("an npm package goes in as a whole, its login file read-write into a config directory of its own")
        void npmPackage() throws IOException {
            Path pkg = Files.createDirectories(tmp.resolve("lib/node_modules/@openai/codex/bin"));
            Path script = Files.writeString(pkg.resolve("codex.js"), "#!/usr/bin/env node");
            Path auth = Files.writeString(tmp.resolve("auth.json"), "{}");
            CliAccess access = new CliAccess(script, List.of(new Bind(auth, "/home/agent/.codex/auth.json", false)),
                    List.of("/home/agent/.codex"), Map.of("CODEX_HOME", "/home/agent/.codex"));

            Plan plan = build(setup(List.of(), List.of(), Map.of("codex", access)), null, "codex", "exec", "-");

            assertThat(mounts(plan)).contains(
                    "type=bind,src=" + pkg.getParent() + ",dst=/usr/lib/node_modules/@openai/codex,readonly",
                    "type=bind,src=" + auth + ",dst=/home/agent/.codex/auth.json");
            assertThat(String.join(" ", plan.command()))
                    .contains("--tmpfs /home/agent/.codex:rw,mode=0700,uid=1000,gid=1001", "-e CODEX_HOME");
            assertThat(plan.command()).endsWith("/usr/lib/node_modules/@openai/codex/bin/codex.js", "exec", "-");
        }

        @Test
        @DisplayName("a shell block gets no AI CLI and no login")
        void shellGetsNoCli() throws IOException {
            Path claude = Files.writeString(tmp.resolve("claude"), "elf");
            Map<String, CliAccess> clis = Map.of("claude",
                    new CliAccess(claude, List.of(), List.of(), Map.of("CLAUDE_CODE_OAUTH_TOKEN", "t")));

            Plan plan = build(setup(List.of(), List.of(), clis), null, "/bin/bash", "-c", "claude -p hi");

            assertThat(plan.clientEnvironment()).isEmpty();
            assertThat(mounts(plan)).noneMatch(m -> m.contains("host-cli"));
        }

        @Test
        @DisplayName("package roots are found for scoped and plain packages, not for a lone binary")
        void packageRoot() {
            assertThat(UnattendedContainerCommand.packageRoot(Path.of("/usr/lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js")))
                    .isEqualTo(Path.of("/usr/lib/node_modules/@earendil-works/pi-coding-agent"));
            assertThat(UnattendedContainerCommand.programTarget("pi", Path.of("/x/node_modules/@earendil-works/pi-coding-agent/dist/cli.js")))
                    .isEqualTo("/usr/lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js");
            assertThat(UnattendedContainerCommand.packageRoot(Path.of("/opt/n/lib/node_modules/tool/bin/t.js")))
                    .isEqualTo(Path.of("/opt/n/lib/node_modules/tool"));
            assertThat(UnattendedContainerCommand.packageRoot(Path.of("/home/u/.local/share/claude/versions/2.1"))).isNull();
        }
    }
}

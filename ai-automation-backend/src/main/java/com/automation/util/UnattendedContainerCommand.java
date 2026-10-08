package com.automation.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the {@code docker run} that starts one AI CLI or shell block of an unattended run in a fresh
 * container (TODO 6ee57b87). Pure apart from reading the file system, so every mount decision is testable
 * without Docker.
 *
 * <p>What goes in: the working directory under its own path (paths in prompts, outputs and the database
 * stay valid), the git metadata of a worktree, directories the caller names as writable (Codex' answer
 * file), and for an AI CLI its program (read-only) and its login. What stays out: the user's home, the data
 * directories with the instance's keys, the database, the Docker socket and every other service of the host
 * user. Secrets inside a mounted directory are covered: a file by an empty read-only file, the database by an
 * empty directory over its folder (journal files and backups appear there later), a data directory likewise.
 * Git's {@code config} and {@code hooks} are read-only: both run commands on the host the next time anybody
 * uses git there. A working directory that is the home directory, contains it, or is a data directory is
 * refused rather than masked piece by piece.
 */
public final class UnattendedContainerCommand {

    static final String HOME = "/home/agent";
    static final String NODE_MODULES = "/usr/lib/node_modules";
    static final String PROGRAMS = "/opt/host-cli";
    static final String CACHE = "/cache";
    static final String LABEL = "org.mysuricates.unattended";
    static final String OWNER_LABEL = "org.mysuricates.owner-pid";

    /** A host path bound into the container. */
    public record Bind(Path source, String target, boolean readOnly) {
        public Bind {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * What one AI CLI gets: its program as found on the host, its login files, private directories owned by
     * the container user (its config home), and its variables — passed by name, the values stay off the
     * command line.
     */
    public record CliAccess(Path program, List<Bind> binds, List<String> privateDirectories,
                            Map<String, String> environment) {
        public CliAccess {
            Objects.requireNonNull(program, "program");
            binds = List.copyOf(binds);
            privateDirectories = List.copyOf(privateDirectories);
            environment = Map.copyOf(environment);
        }
    }

    /**
     * Everything a start needs besides the command, computed per start.
     *
     * @param hiddenPaths    the instance's secrets ({@code ProcessSandboxConfiguration})
     * @param home           the user's home: never mounted, nor anything containing it
     * @param dataDirectories data directories of the instance: never mounted as a whole, empty inside a mount
     * @param cacheVolume    named volume for Maven/npm/pip downloads shared by unattended runs; blank: none
     * @param memory         {@code --memory} value; blank: no limit
     * @param environment    plain variables set inline (time zone and the like), never secrets
     */
    public record Setup(String image, int uid, int gid, Collection<Path> hiddenPaths, Path home, Collection<Path> dataDirectories,
                        String cacheVolume, String memory, int pidsLimit, long ownerPid,
                        Map<String, CliAccess> clis, List<Bind> extraReadOnly, Map<String, String> environment) {
        public Setup {
            Objects.requireNonNull(image, "image");
            hiddenPaths = List.copyOf(hiddenPaths);
            Objects.requireNonNull(home, "home");
            dataDirectories = List.copyOf(dataDirectories);
            clis = Map.copyOf(clis);
            extraReadOnly = List.copyOf(extraReadOnly);
            environment = Map.copyOf(environment);
        }
    }

    /** What to start: the docker arguments, and variables for the docker client's own environment. */
    public record Plan(String name, List<String> command, Map<String, String> clientEnvironment) {
    }

    private UnattendedContainerCommand() {
    }

    public static Plan build(Setup setup, List<String> command, Path workingDirectory, List<Path> writable, String name) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Empty command");
        }
        List<Path> roots = new ArrayList<>();
        List<Path> gitDirs = new ArrayList<>();
        Path workdir = workingDirectory == null ? null : workingDirectory.toAbsolutePath().normalize();
        if (workdir != null) {
            roots.add(workdir);
            gitDirs.addAll(UnattendedContainerMounts.gitDirectories(workdir));
            roots.addAll(gitDirs);
            if (Files.isDirectory(workdir.resolve(".git"))) {
                gitDirs.add(workdir.resolve(".git"));
            }
        }
        writable.stream().map(p -> p.toAbsolutePath().normalize()).forEach(roots::add);
        List<Path> mounted = UnattendedContainerMounts.distinctOutermost(roots);
        mounted.forEach(root -> UnattendedContainerMounts.refuseProtected(root, setup.home(), setup.dataDirectories(),
                setup.hiddenPaths()));

        List<String> args = new ArrayList<>(List.of("docker", "run", "--rm", "-i", "--init", "--pull", "never",
                "--name", name, "--label", LABEL + "=true", "--label", OWNER_LABEL + "=" + setup.ownerPid(),
                "--user", setup.uid() + ":" + setup.gid(),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", String.valueOf(setup.pidsLimit())));
        if (setup.memory() != null && !setup.memory().isBlank()) {
            args.addAll(List.of("--memory", setup.memory().strip()));
        }
        args.addAll(List.of("--tmpfs", "/tmp:rw,exec,mode=1777",
                "--tmpfs", HOME + ":rw,exec,mode=0700,uid=" + setup.uid() + ",gid=" + setup.gid()));
        Map<String, String> inline = new LinkedHashMap<>(setup.environment());
        inline.put("HOME", HOME);
        inline.put("LANG", "C.UTF-8");
        if (setup.cacheVolume() != null && !setup.cacheVolume().isBlank()) {
            args.addAll(List.of("--mount", "type=volume,src=" + setup.cacheVolume().strip() + ",dst=" + CACHE));
            inline.put("MAVEN_OPTS", "-Dmaven.repo.local=" + CACHE + "/m2");
            inline.put("npm_config_cache", CACHE + "/npm");
            inline.put("PIP_CACHE_DIR", CACHE + "/pip");
        }
        for (Path root : mounted) {
            args.addAll(bind(new Bind(root, root.toString(), false)));
        }
        for (String mask : UnattendedContainerMounts.masks(mounted, setup.hiddenPaths(), setup.dataDirectories())) {
            args.addAll(mask.startsWith("tmpfs:")
                    ? List.of("--mount", "type=tmpfs,dst=" + checked(mask.substring("tmpfs:".length())))
                    : bind(new Bind(Path.of("/dev/null"), mask, true)));
        }
        for (Path git : UnattendedContainerMounts.gitReadOnly(gitDirs)) {
            args.addAll(bind(new Bind(git, git.toString(), true)));
        }
        setup.extraReadOnly().forEach(b -> args.addAll(bind(b)));

        List<String> program = new ArrayList<>(command);
        Map<String, String> client = new LinkedHashMap<>();
        CliAccess cli = setup.clis().get(Path.of(command.getFirst()).getFileName().toString());
        if (cli != null) {
            String target = programTarget(command.getFirst(), cli.program());
            Path packageRoot = packageRoot(cli.program());
            args.addAll(bind(packageRoot != null
                    ? new Bind(packageRoot, packageTarget(packageRoot), true)
                    : new Bind(cli.program(), target, true)));
            program.set(0, target);
            for (String dir : cli.privateDirectories()) {
                args.addAll(List.of("--tmpfs", checked(dir) + ":rw,mode=0700,uid=" + setup.uid() + ",gid=" + setup.gid()));
            }
            cli.binds().forEach(b -> args.addAll(bind(b)));
            cli.environment().forEach((key, value) -> {
                args.addAll(List.of("-e", key));
                client.put(key, value);
            });
        }
        inline.forEach((key, value) -> args.addAll(List.of("-e", key + "=" + value)));
        if (workdir != null) {
            args.addAll(List.of("--workdir", workdir.toString()));
        }
        args.add(setup.image());
        args.addAll(program);
        return new Plan(name, List.copyOf(args), Map.copyOf(client));
    }

    /**
     * Where an AI CLI's program lands: an npm package as a whole under {@value #NODE_MODULES} (the script needs
     * its package, Codex its native binary next to it), a single binary under {@value #PROGRAMS}.
     */
    static String programTarget(String name, Path program) {
        Path root = packageRoot(program);
        return root == null ? PROGRAMS + "/" + Path.of(name).getFileName()
                : packageTarget(root) + "/" + root.relativize(program);
    }

    /** {@value #NODE_MODULES}{@code /[@scope/]name} for a package directory. */
    static String packageTarget(Path packageRoot) {
        Path parent = packageRoot.getParent();
        return NODE_MODULES + "/" + (parent.getFileName().toString().startsWith("@")
                ? parent.getFileName() + "/" + packageRoot.getFileName()
                : packageRoot.getFileName().toString());
    }

    /** The npm package a file belongs to ({@code …/node_modules/[@scope/]name}), or null. */
    static Path packageRoot(Path file) {
        for (Path dir = file.getParent(); dir != null && dir.getParent() != null; dir = dir.getParent()) {
            Path parent = dir.getParent();
            if (isNodeModules(parent) && !dir.getFileName().toString().startsWith("@")) {
                return dir;
            }
            if (parent.getFileName() != null && parent.getFileName().toString().startsWith("@")
                    && isNodeModules(parent.getParent())) {
                return dir;
            }
        }
        return null;
    }

    private static boolean isNodeModules(Path dir) {
        return dir != null && dir.getFileName() != null && "node_modules".equals(dir.getFileName().toString());
    }

    private static List<String> bind(Bind b) {
        return List.of("--mount", "type=bind,src=" + checked(b.source().toString()) + ",dst=" + checked(b.target())
                + (b.readOnly() ? ",readonly" : ""));
    }

    /** {@code --mount} splits on commas and quotes; such a path is refused rather than mis-mounted. */
    static String checked(String path) {
        if (path.indexOf(',') >= 0 || path.indexOf('"') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalStateException("Unattended container cannot mount a path with a comma, quote or line break: " + path);
        }
        return path;
    }
}

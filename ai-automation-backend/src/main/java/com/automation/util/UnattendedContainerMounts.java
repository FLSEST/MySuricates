package com.automation.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The path decisions behind {@link UnattendedContainerCommand}: which directories a container gets, which
 * secrets inside them are covered, which git files turn read-only, and which mounts are refused outright.
 */
final class UnattendedContainerMounts {

    private UnattendedContainerMounts() {
    }

    /** {@code <dir>/.git} when it is a file: the worktree's git directory and the repository's common directory. */
    static List<Path> gitDirectories(Path workdir) {
        Path dotGit = workdir.resolve(".git");
        if (!Files.isRegularFile(dotGit)) {
            return List.of();
        }
        try {
            String line = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
            if (!line.startsWith("gitdir:")) {
                return List.of();
            }
            Path gitDir = workdir.resolve(line.substring("gitdir:".length()).strip()).normalize();
            Path common = gitDir;
            Path commonFile = gitDir.resolve("commondir");
            if (Files.isRegularFile(commonFile)) {
                common = gitDir.resolve(Files.readString(commonFile, StandardCharsets.UTF_8).strip()).normalize();
            }
            return Files.isDirectory(common) ? List.of(common, gitDir) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Git's config and hooks in every git directory a mount exposes: read-only. */
    static List<Path> gitReadOnly(List<Path> gitDirs) {
        Set<Path> paths = new LinkedHashSet<>();
        for (Path gitDir : gitDirs) {
            for (String name : List.of("config", "hooks", "config.worktree")) {
                Path p = gitDir.resolve(name);
                if (Files.exists(p)) {
                    paths.add(p);
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Masks for secrets inside a mount, in container paths: {@code tmpfs:<dir>} for a directory, the plain
     * path for a file covered by an empty read-only file. A database (any {@code *.db*}) is covered with its
     * whole folder, unless that folder is a mount itself; a missing file needs no mask.
     */
    static List<String> masks(List<Path> mounted, Collection<Path> hidden, Collection<Path> dataDirectories) {
        List<String> masks = new ArrayList<>();
        for (Path root : mounted) {
            Path realRoot = real(root);
            for (Path dir : dataDirectories) {
                inside(root, realRoot, dir).ifPresent(p -> masks.add("tmpfs:" + p));
            }
            for (Path secret : hidden) {
                inside(root, realRoot, secret).ifPresent(p -> {
                    Path inContainer = Path.of(p);
                    Path folder = inContainer.getParent();
                    if (secret.getFileName() != null && secret.getFileName().toString().contains(".db")
                            && folder != null && !folder.equals(root)) {
                        masks.add("tmpfs:" + folder);
                    } else if (Files.isDirectory(secret)) {
                        masks.add("tmpfs:" + p);
                    } else if (Files.exists(secret)) {
                        masks.add(p);
                    }
                });
            }
        }
        return outermostMasks(masks);
    }

    /** Without duplicates and without masks inside a directory that is masked anyway. */
    private static List<String> outermostMasks(List<String> masks) {
        List<Path> folders = masks.stream().filter(m -> m.startsWith("tmpfs:"))
                .map(m -> Path.of(m.substring("tmpfs:".length()))).toList();
        List<String> result = new ArrayList<>();
        for (String mask : new LinkedHashSet<>(masks)) {
            Path path = Path.of(mask.startsWith("tmpfs:") ? mask.substring("tmpfs:".length()) : mask);
            if (folders.stream().noneMatch(folder -> !folder.equals(path) && path.startsWith(folder))) {
                result.add(mask);
            }
        }
        return result;
    }

    /** Read-only binds for those of {@code paths} that exist under a mount, in container paths. */
    static List<UnattendedContainerCommand.Bind> readOnly(List<Path> mounted, Collection<Path> paths) {
        List<UnattendedContainerCommand.Bind> binds = new ArrayList<>();
        for (Path root : mounted) {
            Path realRoot = real(root);
            for (Path path : paths) {
                if (Files.exists(path)) {
                    inside(root, realRoot, path).ifPresent(p -> binds.add(
                            new UnattendedContainerCommand.Bind(real(path), p, true)));
                }
            }
        }
        return binds;
    }

    /** {@code path} as seen under {@code root} in the container, compared by real paths (symlinks on either side). */
    private static Optional<String> inside(Path root, Path realRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path realPath = real(normalized);
        if (!realPath.startsWith(realRoot) || realPath.equals(realRoot)) {
            return Optional.empty();
        }
        return Optional.of(root.resolve(realRoot.relativize(realPath)).toString());
    }

    /** The real path, or for a missing file its real parent plus its name. */
    static Path real(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            Path parent = normalized.getParent();
            return parent == null || normalized.getFileName() == null ? normalized : real(parent).resolve(normalized.getFileName());
        }
    }

    /**
     * A mount that is, or contains, the home directory, or is a data directory would hand over what it hides,
     * and so would one that lies inside a hidden directory (the backups) or is a hidden file itself. A data
     * directory further down is covered instead ({@link #masks}); one further up holds the run workspaces.
     */
    static void refuseProtected(Path root, Path home, Collection<Path> dataDirectories, Collection<Path> hidden) {
        Path realRoot = real(root);
        if (realRoot.getParent() == null || real(home).startsWith(realRoot)) {
            throw new IllegalStateException("Unattended container refuses to mount " + root
                    + ": it is or contains the home directory — use a project or workspace directory below it");
        }
        for (Path dir : dataDirectories) {
            if (real(dir).equals(realRoot)) {
                throw new IllegalStateException("Unattended container refuses to mount " + root
                        + ": it is a data directory of the instance");
            }
        }
        for (Path secret : hidden) {
            Path realSecret = real(secret);
            if (realRoot.equals(realSecret) || Files.isDirectory(realSecret) && realRoot.startsWith(realSecret)) {
                throw new IllegalStateException("Unattended container refuses to mount " + root
                        + ": it lies in the instance's secrets (" + secret + ")");
            }
        }
    }

    /** Drops roots that lie inside another root (a worktree's git directory inside its repository's). */
    static List<Path> distinctOutermost(List<Path> roots) {
        List<Path> result = new ArrayList<>();
        for (Path root : new LinkedHashSet<>(roots)) {
            if (roots.stream().noneMatch(other -> !other.equals(root) && root.startsWith(other))) {
                result.add(root);
            }
        }
        return result;
    }
}

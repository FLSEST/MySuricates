package com.automation.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    /**
     * What of git a working directory needs: further roots to mount (the repository of a worktree), the git
     * directories whose config and hooks turn read-only, and pointer files that turn read-only themselves.
     */
    record GitLayout(List<Path> extraRoots, List<Path> gitDirs, List<Path> readOnlyFiles) {
        static final GitLayout NONE = new GitLayout(List.of(), List.of(), List.of());
    }

    /**
     * A {@code .git} directory as it is; a {@code .git} file only when its pointers hold up: the git directory
     * it names carries a {@code gitdir} file pointing back at this {@code .git}, and lies under
     * {@code <common>/worktrees} of a real git directory. Only git writes such a back-pointer into a directory
     * a run cannot reach, so a pointer an agent rewrote cannot steer a mount at another host directory (review
     * 2026-10-09). The three pointer files are mounted read-only.
     */
    static GitLayout gitLayout(Path workdir) {
        Path dotGit = workdir.resolve(".git");
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return new GitLayout(List.of(), List.of(dotGit), List.of());
        }
        if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return GitLayout.NONE;
        }
        try {
            String line = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
            if (!line.startsWith("gitdir:")) {
                return GitLayout.NONE;
            }
            Path gitDir = real(workdir.resolve(line.substring("gitdir:".length()).strip()));
            Path back = gitDir.resolve("gitdir");
            Path commonFile = gitDir.resolve("commondir");
            if (!Files.isRegularFile(back, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(commonFile, LinkOption.NOFOLLOW_LINKS)
                    || !real(workdir.resolve(Files.readString(back, StandardCharsets.UTF_8).strip())).equals(real(dotGit))) {
                return GitLayout.NONE;
            }
            Path common = real(gitDir.resolve(Files.readString(commonFile, StandardCharsets.UTF_8).strip()));
            if (!real(common.resolve("worktrees")).equals(gitDir.getParent()) || !isGitDirectory(common)) {
                return GitLayout.NONE;
            }
            return new GitLayout(List.of(common), List.of(common, gitDir), List.of(dotGit, back, commonFile));
        } catch (IOException | RuntimeException e) {
            return GitLayout.NONE;
        }
    }

    private static boolean isGitDirectory(Path dir) {
        return Files.isRegularFile(dir.resolve("HEAD")) && Files.isDirectory(dir.resolve("objects"))
                && Files.isDirectory(dir.resolve("refs"));
    }

    /**
     * Every directory strictly between a mount root and one of {@code paths}, bound onto itself: a mountpoint
     * cannot be renamed or removed, so a read-only path cannot be moved aside and replaced by a writable
     * one (review 2026-10-09). In container paths, without duplicates.
     */
    static List<UnattendedContainerCommand.Bind> pins(List<Path> mounted, Collection<Path> paths) {
        Set<String> seen = new LinkedHashSet<>();
        List<UnattendedContainerCommand.Bind> binds = new ArrayList<>();
        for (Path root : mounted) {
            Path realRoot = real(root);
            for (Path path : paths) {
                Path realPath = real(path);
                if (!realPath.startsWith(realRoot) || realPath.equals(realRoot)) {
                    continue;
                }
                for (Path dir = realPath.getParent(); dir != null && !dir.equals(realRoot); dir = dir.getParent()) {
                    String target = root.resolve(realRoot.relativize(dir)).toString();
                    if (seen.add(target)) {
                        binds.add(new UnattendedContainerCommand.Bind(dir, target, false));
                    }
                }
            }
        }
        return binds;
    }

    /** Git's config and hooks in every git directory a mount exposes, plus the pointer files: read-only. */
    static List<Path> gitReadOnly(GitLayout git) {
        Set<Path> paths = new LinkedHashSet<>(git.readOnlyFiles());
        for (Path gitDir : git.gitDirs()) {
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

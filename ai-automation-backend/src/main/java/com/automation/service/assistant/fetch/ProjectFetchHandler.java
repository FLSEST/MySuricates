package com.automation.service.assistant.fetch;

import com.automation.repository.ProjectRepository;
import com.automation.service.assistant.format.AssistantFormatUtils;
import com.automation.service.project.ProjectContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fetch handler for project file access: AI project analysis, directory listings
 * and sandboxed text file reads ({@code project_context}, {@code project_files},
 * {@code project_file}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectFetchHandler {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "txt", "java", "py", "ts", "tsx", "js", "jsx", "json", "yml", "yaml",
            "xml", "toml", "gradle", "properties", "sh", "bash", "sql", "html", "css",
            "scss", "rs", "go", "kt", "kts", "swift", "rb", "php", "c", "h", "cpp",
            "hpp", "cs", "r", "lua", "vim", "conf", "cfg", "ini", "env", "dockerfile",
            "makefile", "gitignore", "editorconfig", "lock"
    );

    private final ProjectRepository projectRepository;
    private final ProjectContextService projectContextService;

    public String formatProjectContext(String projectId) {
        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Project '" + projectId + "' not found.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Project '" + project.getName() + "' has no path configured.\n";

            // Cache-first: use stored aiContext if available
            String context = project.getAiContext();
            if (context == null || context.isBlank()) {
                context = projectContextService.generateContext(project.getPath());
                if (context != null && !context.isBlank()) {
                    project.setAiContext(context);
                    projectRepository.save(project);
                }
            }

            if (context == null || context.isBlank())
                return "No project context available for '" + project.getName() + "'.\n";
            return "**Project analysis: " + project.getName() + "** (path: " + project.getPath() + ")\n\n" + context + "\n";
        } catch (Exception e) {
            log.debug("Could not generate project context for {}", projectId, e);
            return "Project context could not be generated: " + e.getMessage() + "\n";
        }
    }

    public String formatProjectFiles(String projectId) {
        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Project '" + projectId + "' not found.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Project '" + project.getName() + "' has no path configured.\n";

            Path root = Path.of(project.getPath());
            if (!Files.isDirectory(root))
                return "Project path does not exist: " + project.getPath() + "\n";

            var sb = new StringBuilder();
            sb.append("**Project files: ").append(project.getName()).append("** (").append(project.getPath()).append(")\n\n");

            // Top-level files
            int count = 0;
            try (Stream<Path> topLevel = Files.list(root).sorted()) {
                for (Path entry : topLevel.toList()) {
                    if (count >= 50) { sb.append("  … (more files)\n"); break; }
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) {
                        if (!AssistantFormatUtils.IGNORED_DIRS.contains(name)) {
                            sb.append("  ").append(name).append("/\n");
                            appendSubdirectory(sb, entry, 1, count);
                        }
                    } else {
                        long size = Files.size(entry);
                        sb.append("  ").append(name).append(" (").append(formatSize(size)).append(")\n");
                    }
                    count++;
                }
            }

            sb.append("\nUse `<fetch type=\"project_file\" id=\"").append(projectId)
                    .append("/<path>\"/>` to read a file.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not list project files for {}", projectId, e);
            return "Project files could not be loaded: " + e.getMessage() + "\n";
        }
    }

    private void appendSubdirectory(StringBuilder sb, Path dir, int depth, int parentCount) {
        if (depth > 1 || parentCount > 40) return;
        try (Stream<Path> entries = Files.list(dir).sorted()) {
            int subCount = 0;
            for (Path entry : entries.toList()) {
                if (subCount >= 10) { sb.append("    … (more)\n"); break; }
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (!AssistantFormatUtils.IGNORED_DIRS.contains(name)) {
                        sb.append("    ").append(name).append("/\n");
                        subCount++;
                    }
                } else {
                    sb.append("    ").append(name).append("\n");
                    subCount++;
                }
            }
        } catch (IOException ignored) {}
    }

    public String formatProjectFile(String idWithPath) {
        int slash = idWithPath.indexOf('/');
        if (slash < 0) return "Invalid format. Expected: <project-id>/<filePath>\n";
        String projectId = idWithPath.substring(0, slash);
        String relativePath = idWithPath.substring(slash + 1);

        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Project '" + projectId + "' not found.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Project '" + project.getName() + "' has no path configured.\n";

            Path root = Path.of(project.getPath()).toAbsolutePath().normalize();
            Path resolved = root.resolve(relativePath).normalize();

            // Path traversal protection
            if (!resolved.startsWith(root))
                return "Access denied: path is outside the project.\n";

            if (!Files.exists(resolved))
                return "File not found: " + relativePath + "\n";

            if (Files.isDirectory(resolved))
                return "'" + relativePath + "' is a directory. Use `<fetch type=\"project_files\">` for listings.\n";

            // File type check
            String fileName = resolved.getFileName().toString().toLowerCase();
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
            boolean isTextFile = TEXT_EXTENSIONS.contains(ext)
                    || fileName.equals("makefile") || fileName.equals("dockerfile")
                    || fileName.startsWith(".");
            if (!isTextFile)
                return "File '" + relativePath + "' is not a text file (." + ext + ").\n";

            long fileSize = Files.size(resolved);
            if (fileSize > 500_000)
                return "File '" + relativePath + "' is too large (" + formatSize(fileSize) + "). Max 500 KB.\n";

            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            var sb = new StringBuilder();
            sb.append("**File: ").append(relativePath).append("** (").append(formatSize(fileSize)).append(")\n\n");
            if (content.length() > 8000) {
                sb.append(content, 0, 8000);
                sb.append("\n\n[File truncated: 8000 of ").append(content.length()).append(" characters]\n");
            } else {
                sb.append(content).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not read project file {}", idWithPath, e);
            return "File could not be read: " + e.getMessage() + "\n";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}

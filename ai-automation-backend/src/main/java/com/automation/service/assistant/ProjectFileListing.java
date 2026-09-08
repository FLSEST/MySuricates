package com.automation.service.assistant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * Appends a compact "Project files:" line (top-level files plus up to five files from
 * {@code docs/}) and the {@code project_file} fetch hint to an assistant prompt.
 *
 * <p>Extracted from {@code AssistantContextBuilder} (Audit-TODO cb80664c). Not an
 * {@code AssistantContextSection} — it needs the project path, not the page category.
 */
@Component
@Slf4j
public class ProjectFileListing {

    public void append(StringBuilder sb, String projectPath, String projectId) {
        if (projectPath == null || projectPath.isBlank()) return;
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) return;

        try (Stream<Path> topLevel = Files.list(root).sorted()) {
            var files = new ArrayList<String>();
            for (Path entry : topLevel.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (!AssistantFormatUtils.IGNORED_DIRS.contains(name) && name.equals("docs")) {
                        try (Stream<Path> docs = Files.list(entry).sorted()) {
                            docs.filter(Files::isRegularFile)
                                    .limit(5)
                                    .forEach(f -> files.add("docs/" + f.getFileName()));
                        }
                    }
                } else {
                    files.add(name);
                }
            }
            if (!files.isEmpty()) {
                String listing = String.join(", ", files);
                if (listing.length() > 300) listing = listing.substring(0, 300) + "…";
                sb.append("Project files: ").append(listing).append("\n");
                sb.append("_(Use `<fetch type=\"project_file\" id=\"").append(projectId)
                        .append("/<path>\"/>` to read a file)_\n");
            }
        } catch (IOException e) {
            log.debug("Could not list project files for {}", projectPath, e);
        }
    }
}

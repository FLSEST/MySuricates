package com.automation.service.project;

import com.automation.exception.AiProviderException;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectContextService {

    private static final String CONTEXT_FILE_PATH = com.automation.service.ai.RunContextFiles.PROJECT_CONTEXT;
    private static final List<String> PROBE_FILES = List.of(
            "README.md", "README.rst", "README.txt",
            "package.json", "pom.xml", "build.gradle", "Cargo.toml",
            "pyproject.toml", "setup.py", "requirements.txt",
            "go.mod", "Makefile", "Dockerfile", "docker-compose.yml",
            "CLAUDE.md", ".claude/settings.json"
    );

    private final AiProviderRegistry aiProviderRegistry;

    /**
     * Writes the project AI context to .claude/rules/project-context.md in the project directory.
     * Called before workflow execution so the executing agent can read it.
     * Deletes the file if aiContext is null or blank.
     */
    public void writeProjectContextToProject(String aiContext, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("No project path — skipping project context file write");
            return;
        }

        Path contextFile = Path.of(projectPath, CONTEXT_FILE_PATH);

        if (aiContext != null && !aiContext.isBlank()) {
            try {
                Files.createDirectories(contextFile.getParent());
                Files.writeString(contextFile, aiContext);
                log.info("Wrote project context to {}", contextFile);
            } catch (IOException e) {
                log.warn("Failed to write project context file to {}: {}", contextFile, e.getMessage());
            }
        } else {
            try {
                Files.deleteIfExists(contextFile);
            } catch (IOException e) {
                log.debug("Failed to delete stale project context file: {}", e.getMessage());
            }
        }
    }

    /**
     * Generates AI context markdown by analyzing the project directory.
     * Reads key project files (README, package.json, pom.xml, etc.) and
     * sends them to Claude to produce a concise project context document.
     */
    public String generateContext(String projectPath) {
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project path does not exist: " + projectPath);
        }

        StringBuilder snapshot = new StringBuilder();

        // Collect top-level directory listing
        snapshot.append("## Directory listing (top-level)\n```\n");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") && !name.equals(".claude")) continue;
                snapshot.append(Files.isDirectory(entry) ? name + "/\n" : name + "\n");
            }
        } catch (IOException e) {
            snapshot.append("(could not list directory)\n");
        }
        snapshot.append("```\n\n");

        // Read probe files
        for (String probe : PROBE_FILES) {
            Path file = root.resolve(probe);
            if (Files.isRegularFile(file)) {
                try {
                    String content = Files.readString(file);
                    // Truncate large files
                    if (content.length() > 4000) {
                        content = content.substring(0, 4000) + "\n... (truncated)";
                    }
                    snapshot.append("## ").append(probe).append("\n```\n");
                    snapshot.append(content);
                    snapshot.append("\n```\n\n");
                } catch (IOException e) {
                    log.debug("Could not read probe file {}: {}", probe, e.getMessage());
                }
            }
        }

        // Also check src/ structure one level deep
        Path srcDir = root.resolve("src");
        if (Files.isDirectory(srcDir)) {
            snapshot.append("## src/ structure\n```\n");
            collectTree(srcDir, 2, snapshot, "");
            snapshot.append("```\n\n");
        }

        String prompt = """
                You are analyzing a software project to generate a concise AI context document.
                This document will be used to prime AI agents working on this project.

                Based on the project snapshot below, generate a Markdown document that covers:
                1. **Project overview** — what it is, what it does (1-2 sentences)
                2. **Tech stack** — language, framework, build tool, key dependencies
                3. **Project structure** — key directories and their purpose
                4. **Conventions** — coding style, naming, patterns observed
                5. **Build & run** — how to build, test, and run the project
                6. **Important notes** — anything an AI agent should know

                Keep it concise (under 150 lines). Use bullet points. Be factual, not speculative.
                Only include sections where you have enough information.
                Do NOT include a title/heading — the system adds one.

                PROJECT SNAPSHOT:
                """ + snapshot;

        try {
            AiProvider.ReasoningResult result = aiProviderRegistry.getDefault().reason(
                    new AiProvider.ReasoningRequest(prompt));
            return result.text();
        } catch (IOException e) {
            // Logged once by the exception handler, with the cause.
            throw new AiProviderException("Failed to generate project context: " + e.getMessage(), e);
        }
    }

    private void collectTree(Path dir, int maxDepth, StringBuilder sb, String indent) {
        if (maxDepth <= 0) return;
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        } catch (IOException e) {
            return;
        }
        entries.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (name.startsWith(".")) continue;
            if (Files.isDirectory(entry)) {
                sb.append(indent).append(name).append("/\n");
                collectTree(entry, maxDepth - 1, sb, indent + "  ");
            } else {
                sb.append(indent).append(name).append("\n");
            }
        }
    }
}

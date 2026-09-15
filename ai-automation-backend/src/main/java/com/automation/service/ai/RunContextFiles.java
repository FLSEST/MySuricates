package com.automation.service.ai;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The run context the platform writes into the project before AI calls: project AI context, skills
 * (workflow skills merged with the current block's skills) and the active workflow memory.
 *
 * <p>The Claude CLI loads {@code .claude/rules/} from its working directory by itself. pi (started
 * with {@code --no-context-files}) and Codex do not, so their providers append the same files to
 * the system prompt via {@link #withRunContext} — one source for every provider, no provider-ID
 * checks, and no double injection for Claude.
 */
@Slf4j
public final class RunContextFiles {

    public static final String PROJECT_CONTEXT = ".claude/rules/project-context.md";
    public static final String SKILLS_CONTEXT = ".claude/rules/skills-context.md";
    public static final String WORKFLOW_MEMORY = ".claude/rules/project-memory.md";

    static final List<String> ALL = List.of(PROJECT_CONTEXT, SKILLS_CONTEXT, WORKFLOW_MEMORY);

    private RunContextFiles() {}

    /** {@code systemPrompt} followed by the run context files found in {@code workingDirectory}. */
    public static String withRunContext(String systemPrompt, String workingDirectory) {
        String context = read(workingDirectory);
        if (context.isEmpty()) {
            return systemPrompt;
        }
        return systemPrompt == null || systemPrompt.isBlank() ? context : systemPrompt + "\n\n" + context;
    }

    static String read(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        for (String file : ALL) {
            Path path = Path.of(workingDirectory, file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                String content = Files.readString(path).trim();
                if (!content.isEmpty()) {
                    context.append(context.isEmpty() ? "" : "\n\n").append(content);
                }
            } catch (IOException e) {
                log.warn("Run context file {} could not be read — the provider runs without it: {}", path, e.getMessage());
            }
        }
        return context.toString();
    }
}

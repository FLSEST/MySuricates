package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Run-owned Git snapshots. Slow preparation deliberately takes place outside a transaction. */
@Service
public class RunWorkspaceService {
    private final WorkflowRunRepository runs;
    private final ObjectMapper mapper;
    private final String root;

    public RunWorkspaceService(WorkflowRunRepository runs, ObjectMapper mapper,
            @Value("${automation.run-workspace.root:${user.home}/.mysuricates/workspaces}") String root) {
        this.runs = runs;
        this.mapper = mapper;
        this.root = root;
    }

    /** Template blocks retain this policy even where the template format has no metadata field. */
    public static Map<String, Object> policy(Map<String, Object> metadata, List<BlockDefinition> blocks) {
        Map<String, Object> effective = new HashMap<>(metadata);
        if (!effective.containsKey("workspace") && !blocks.isEmpty()
                && blocks.getFirst().getConfig().get("workspace") instanceof Map<?, ?> workspace) {
            effective.put("workspace", Map.copyOf(workspace));
        }
        return Map.copyOf(effective);
    }

    public static Map<String, Object> policyForRun(WorkflowRun run, Map<String, Object> metadata) {
        if (!(run.getMetadata().get("workspacePolicy") instanceof Map<?, ?> policy)) return metadata;
        Map<String, Object> effective = new HashMap<>(metadata);
        effective.put("workspace", policy);
        return effective;
    }

    public static boolean enabled(Map<String, Object> metadata) {
        if (!(metadata.get("workspace") instanceof Map<?, ?> config)) return false;
        Object mode = config.get("mode");
        if (mode == null || "project".equals(mode)) return false;
        if (!"isolated".equals(mode)) throw new IllegalArgumentException("Unknown workspace mode: " + mode);
        return true;
    }

    /** Called by the execution thread before the concurrency queue, outside DB transactions. */
    public void pinSource(WorkflowRun run, Project project, Map<String, Object> metadata) {
        pinSource(run, project, metadata, Map.of());
    }

    public void pinSource(WorkflowRun run, Project project, Map<String, Object> metadata,
                          Map<String, Object> input) {
        if (!enabled(metadata) || run.getMetadata().containsKey("workspaceSource")) return;
        Map<?, ?> config = (Map<?, ?>) metadata.get("workspace");
        String source = config.get("source_path") instanceof String path ? path : project.getPath();
        Map<String, Object> saved = new HashMap<>(run.getMetadata());
        saved.put("workspaceSource", pinRepository(source, input));
        Map<String, Object> additional = new HashMap<>();
        if (config.get("additional_sources") instanceof Map<?, ?> sources) {
            for (var entry : sources.entrySet()) {
                String alias = String.valueOf(entry.getKey());
                if (!alias.matches("[a-zA-Z][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid repository alias");
                additional.put(alias, pinRepository(String.valueOf(entry.getValue()), input));
            }
        }
        saved.put("workspaceAdditionalSources", additional);
        saved.put("workspacePolicy", Map.copyOf(config));
        run.setMetadata(saved);
        runs.save(run);
    }

    private Map<String, Object> pinRepository(String source, Map<String, Object> input) {
        var placeholders = java.util.regex.Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}").matcher(source);
        source = placeholders.replaceAll(match -> {
            String segment = String.valueOf(input.getOrDefault(match.group(1), ""));
            if (!segment.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"))
                throw new IllegalArgumentException("Source path variable must be one repository name: " + match.group(1));
            return java.util.regex.Matcher.quoteReplacement(segment);
        });
        Process process = null;
        try {
            process = new ProcessBuilder("git", "-C", source, "rev-parse", "--verify", "HEAD")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("Git HEAD lookup timed out");
            String sha = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || !sha.matches("[a-f0-9]{40,64}"))
                throw new IllegalStateException("Cannot resolve local HEAD for " + source);
            return Map.of("path", source, "commit", sha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Source selection interrupted");
        } catch (Exception e) {
            throw new WorkflowExecutionException("Cannot pin workspace source: " + e.getMessage(), e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    public Project prepare(WorkflowRun run, Project project, Map<String, Object> metadata,
                           ExecutionContext context, boolean resume) {
        if (!enabled(metadata)) return project;
        Map<?, ?> config = (Map<?, ?>) metadata.get("workspace");
        String source = config.get("source_path") instanceof String path ? path : project.getPath();
        if (run.getMetadata().get("workspaceSource") instanceof Map<?, ?> selected)
            source = String.valueOf(selected.get("path"));
        String mode = config.get("source") instanceof String value ? value : "commit";
        if ("WORKTREE".equals(context.getVariable("git_target"))
                && Boolean.TRUE.equals(config.get("allow_working_tree_target"))) mode = "working-tree";
        if (resume && run.getMetadata().get("workspace") instanceof Map<?, ?> original) {
            source = String.valueOf(original.get("sourcePath"));
            mode = String.valueOf(original.get("sourceMode"));
        }
        List<String> args = new ArrayList<>(List.of("--source", source, "--root", root,
                "--project", project.getId(), "--run", run.getId(), "--mode", mode));
        if (run.getMetadata().get("workspaceSource") instanceof Map<?, ?> pinned) {
            args.addAll(List.of("--commit", String.valueOf(pinned.get("commit"))));
        }
        if (resume) args.add("--resume");
        if (Boolean.TRUE.equals(config.get("branch"))) args.add("--branch");
        if (Boolean.TRUE.equals(config.get("install_npm"))) args.add("--npm");
        Map<String, Object> manifest = invoke(args, context);
        WorkspaceInputPaths.remap(context, manifest);
        String executionPath = String.valueOf(manifest.get("executionPath"));
        context.setProjectPath(executionPath);
        context.setVariable("execution_path", executionPath);
        context.setVariable("workspace_path", manifest.get("workspacePath"));
        context.setVariable("workspace_logs", manifest.get("logsPath"));
        context.setVariable("workspace_source_sha", manifest.get("sourceCommit"));
        context.setRunMetadata("workspace", manifest);
        Map<String, Object> persisted = new HashMap<>(run.getMetadata());
        persisted.put("workspace", manifest);
        Map<String, Object> additional = prepareAdditional(run, project, config, context, mode, resume);
        persisted.put("workspaceAdditional", additional);
        context.setRunMetadata("workspaceAdditional", additional);
        String prefix = context.getInstructionPrefix() == null ? "" : context.getInstructionPrefix();
        prefix = prefix.replace("{{ execution_path }}", executionPath)
                .replace("{{ workspace_logs }}", String.valueOf(manifest.get("logsPath")))
                .replace("{{ workspace_source_sha }}", String.valueOf(manifest.get("sourceCommit")));
        context.setInstructionPrefix("Run-Arbeitsverzeichnis: " + executionPath + ". Projekt-ID: " + project.getId()
                + ". Code und Build-Ausgaben ausschliesslich im Run-Workspace bearbeiten. Originalprojekt: "
                + project.getPath() + " (nicht beschreiben).\n\n" + prefix);
        run.setMetadata(persisted);
        runs.save(run); // Persist before any step: restart/resume must retain the exact snapshot.
        return Project.builder().id(project.getId()).name(project.getName()).type(project.getType())
                .path(executionPath).config(new HashMap<>(project.getConfig()))
                .aiContext(project.getAiContext()).status(project.getStatus()).build();
    }

    private Map<String, Object> prepareAdditional(WorkflowRun run, Project project, Map<?, ?> config,
                                                 ExecutionContext context, String mode, boolean resume) {
        Map<String, Object> manifests = new HashMap<>();
        if (!(run.getMetadata().get("workspaceAdditionalSources") instanceof Map<?, ?> sources)) return manifests;
        for (var entry : sources.entrySet()) {
            String alias = String.valueOf(entry.getKey());
            Map<?, ?> pinned = (Map<?, ?>) entry.getValue();
            List<String> args = new ArrayList<>(List.of("--source", String.valueOf(pinned.get("path")),
                    "--commit", String.valueOf(pinned.get("commit")), "--root", root,
                    "--project", project.getId(), "--run", run.getId(), "--slot", alias, "--mode", mode));
            if (resume) args.add("--resume");
            if (Boolean.TRUE.equals(config.get("branch"))) args.add("--branch");
            if (Boolean.TRUE.equals(config.get("install_npm"))) args.add("--npm");
            Map<String, Object> manifest = invoke(args, context);
            manifests.put(alias, manifest);
            WorkspaceInputPaths.remap(context, manifest);
            context.setVariable("execution_path_" + alias, manifest.get("executionPath"));
        }
        return manifests;
    }

    private Map<String, Object> invoke(List<String> args, ExecutionContext context) {
        Process process = null;
        try {
            String script;
            try (var stream = new ClassPathResource("scripts/run-workspace.py").getInputStream()) {
                script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<String> command = new ArrayList<>(List.of("python3", "-c", script));
            command.addAll(args);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            context.registerProcess(process);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process child = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stream = child.getInputStream()) { stream.transferTo(output); }
                catch (java.io.IOException ignored) { /* Process cancellation closes the stream. */ }
            });
            Instant deadline = Instant.now().plusSeconds(1800);
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (context.shouldStop()) throw new CancellationException("Workspace preparation cancelled");
                if (Instant.now().isAfter(deadline)) throw new IllegalStateException("Workspace preparation timed out");
            }
            reader.join(5000);
            if (context.shouldStop()) throw new CancellationException("Workspace preparation cancelled");
            String text = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new IllegalStateException(text);
            return mapper.readValue(text, new TypeReference<>() {});
        } catch (CancellationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Workspace preparation interrupted");
        } catch (Exception e) {
            throw new WorkflowExecutionException("Cannot prepare isolated workspace: " + e.getMessage(), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            context.clearProcess();
        }
    }
}

package com.automation.executor.blocks;

import com.automation.config.DataHome;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.cli.ContainerToolRequest;
import com.automation.service.cli.ShellCommandService.ShellResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tool mode of the shell block (TODO d3a7f5e2): a container image runs with its own entrypoint and the
 * block's {@code container_args}, no shell in between. With {@code output_format: sarif} the platform mounts a
 * fresh output directory, turns the SARIF files there into a fact sheet ({@link SarifFactSheet}) and removes
 * the directory again. Arguments never pass a shell, so untrusted placeholders are refused instead of quoted:
 * argument injection ({@code --config=…}) cannot be defused in general.
 */
@Component
class ContainerToolStep {

    private final ContainerShellService containerShellService;
    private final Path cacheRoot;

    @Autowired
    ContainerToolStep(ContainerShellService containerShellService) {
        this(containerShellService, DataHome.dir().resolve("tool-cache"));
    }

    ContainerToolStep(ContainerShellService containerShellService, Path cacheRoot) {
        this.containerShellService = containerShellService;
        this.cacheRoot = cacheRoot;
    }

    StepResult execute(BlockDefinition block, ContainerToolSpec spec, ExecutionContext context) {
        if (spec.image() == null) {
            throw new WorkflowExecutionException("Shell block with 'container_args' requires a 'container_image'");
        }
        if (spec.args() == null) {
            throw new WorkflowExecutionException("'container_args' must be a list of strings");
        }
        List<String> args = resolveArgs(spec.args(), context);
        ContainerToolRequest.Mount cache = cacheMount(spec);
        Path outputDir = spec.sarif() ? createOutputDir() : null;
        try {
            ContainerToolRequest request = new ContainerToolRequest(spec.image(), args, context.getProjectPath(),
                    outputDir == null ? null : new ContainerToolRequest.Mount(outputDir, spec.outputDir()), cache,
                    ShellBlockExecutor.resolveMounts(spec.mounts(), context), spec.mountDockerSocket(),
                    spec.timeoutSeconds());
            StepResult step = new StepResult();
            step.setStepIndex(context.nextStepIndex());
            step.setStartedAt(Instant.now());
            step.setInput(spec.image() + (args.isEmpty() ? "" : " " + String.join(" ", args)));
            step.setStatus(StepStatus.RUNNING);

            ShellResult result = containerShellService.executeTool(request, context::shouldStop);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("exitCode", result.exitCode());
            metadata.put("executionMode", "container-tool:" + spec.image());
            metadata.put("outputFormat", spec.sarif() ? "sarif" : "raw");
            if (spec.sarif()) {
                SarifFactSheet.Result sheet = SarifFactSheet.read(outputDir, spec.outputDir(), spec.factPrefix(),
                        result.exitCode(), result.output());
                metadata.put("factPrefix", spec.factPrefix());
                metadata.put("measured", sheet.measured());
                metadata.put("findings", sheet.findings());
                finish(step, sheet.text(), sheet.measured(), result, metadata);
            } else {
                finish(step, result.output(), result.exitCode() == 0, result, metadata);
            }
            WorkspaceCheckEvidence.record(context, step, result);
            return step;
        } finally {
            if (outputDir != null) delete(outputDir);
        }
    }

    private static List<String> resolveArgs(List<String> raw, ExecutionContext context) {
        String projectPath = context.getProjectPath();
        boolean workspace = context.getRunMetadata().containsKey("workspace") && projectPath != null;
        List<String> resolved = new ArrayList<>(raw.size());
        for (String arg : raw) {
            if (context.namesEscapedValue(arg)) {
                throw new WorkflowExecutionException("container_args must not contain untrusted values (webhook input, "
                        + "tainted block output, platform data such as open_todos): " + arg);
            }
            String value = context.resolveTemplate(arg);
            resolved.add(workspace ? value.replace(projectPath, "/workspace") : value);
        }
        return resolved;
    }

    private ContainerToolRequest.Mount cacheMount(ContainerToolSpec spec) {
        if (spec.cacheDir() == null) return null;
        Path dir = cacheRoot.resolve(cacheSlug(spec.image()));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new WorkflowExecutionException("Cannot create tool cache " + dir + ": " + e.getMessage(), e);
        }
        return new ContainerToolRequest.Mount(dir, spec.cacheDir());
    }

    /** Image name without tag or digest, as one safe path segment: {@code registry:5000/a/b:1} → {@code registry_5000_a_b}. */
    static String cacheSlug(String image) {
        String name = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        if (colon > slash) name = name.substring(0, colon);
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        return slug.isEmpty() || slug.startsWith(".") ? "_" + slug : slug;
    }

    private static Path createOutputDir() {
        try {
            return Files.createTempDirectory("mysuricates-tool-");
        } catch (IOException e) {
            throw new WorkflowExecutionException("Cannot create the tool output directory: " + e.getMessage(), e);
        }
    }

    private static void finish(StepResult step, String output, boolean ok, ShellResult result, Map<String, Object> metadata) {
        step.setOutput(output);
        step.setStatus(ok ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setCompletedAt(Instant.now());
        step.setDurationMs(result.durationMs());
        step.setMetadata(metadata);
    }

    /** Spring's deleteRecursively walks without following symlinks — a link a tool left behind is removed, not its target. */
    private static void delete(Path dir) {
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException ignored) {
            // a leftover temp directory is harmless; the run result must not depend on it
        }
    }
}

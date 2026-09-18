package com.automation.architecture;

import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

/**
 * Gate: everything we ship must survive the save-time block-type check (TODO 89dd9e34).
 *
 * <p>Since unknown block types are refused by {@code BlockTypeValidator}, a built-in template or a
 * pool workflow with a type no executor supports could no longer be installed. This test reads the
 * shipped JSON and checks every block type — nested ones included — against
 * {@link BlockTypeRegistry}.
 */
class ShippedWorkflowBlockTypesTest {

    private static final Path BUILTIN_TEMPLATES = Path.of("src/main/resources/templates/builtin");
    /** The pool lives next to the backend module, in the repository root. */
    private static final Path WORKFLOW_POOL = Path.of("../workflow-pool");
    private static final String EXECUTOR_PACKAGE = "com.automation.executor";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BlockTypeRegistry registry = new BlockTypeRegistry(scannedExecutors());

    @Test
    void everyBuiltinTemplateUsesKnownBlockTypes() throws IOException {
        assertKnownTypes(BUILTIN_TEMPLATES);
    }

    @Test
    void everyPooledWorkflowUsesKnownBlockTypes() throws IOException {
        assertKnownTypes(WORKFLOW_POOL);
    }

    private void assertKnownTypes(Path directory) throws IOException {
        assertThat(directory).as("shipped workflow directory").isDirectory();
        List<String> problems = new ArrayList<>();
        int blocks = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode root = objectMapper.readTree(Files.readString(file));
                blocks += collectProblems(root.get("blocks"), file.getFileName().toString(), problems);
            }
        }
        assertThat(problems).as("unknown block types in " + directory
                + " (known: " + registry.knownTypesAsText() + ")").isEmpty();
        assertThat(blocks).as("blocks found in " + directory).isPositive();
    }

    /** @return the number of blocks seen, including nested ones */
    private int collectProblems(JsonNode blocks, String file, List<String> problems) {
        if (blocks == null || !blocks.isArray()) {
            return 0;
        }
        int seen = 0;
        for (JsonNode block : blocks) {
            seen++;
            JsonNode type = block.get("type");
            if (type == null || !type.isTextual() || type.asText().isBlank()) {
                problems.add(file + ": block without a type");
            } else if (!registry.isKnown(type.asText())) {
                problems.add(file + ": block '" + type.asText() + "'");
            }
            JsonNode config = block.get("config");
            if (config == null || !config.isObject()) {
                continue;
            }
            JsonNode cases = config.get("cases");
            if (cases != null && cases.isArray()) {
                for (JsonNode caseEntry : cases) {
                    seen += collectProblems(caseEntry.get("blocks"), file, problems);
                }
            }
            seen += collectProblems(config.get("default"), file, problems);
            seen += collectProblems(config.get("blocks"), file, problems);
        }
        return seen;
    }

    /** Executors are probed without running their constructors — {@code supportedTypes()} is a constant. */
    private static List<BlockExecutor> scannedExecutors() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BlockExecutor.class));
        List<BlockExecutor> executors = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(EXECUTOR_PACKAGE)) {
            try {
                executors.add((BlockExecutor) Mockito.mock(Class.forName(bd.getBeanClassName()),
                        withSettings().defaultAnswer(CALLS_REAL_METHODS)));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(executors).as("BlockExecutor implementations").isNotEmpty();
        return executors;
    }
}

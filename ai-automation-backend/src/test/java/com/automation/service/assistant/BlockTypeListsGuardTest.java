package com.automation.service.assistant;

import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeRegistry;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.assistant.fetch.BlockSpecs;
import com.automation.service.assistant.fetch.DocsFetchHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

/**
 * Guards the hand-maintained block-type lists against the executors that actually exist.
 *
 * <p>Source of truth is the set of concrete {@link BlockExecutor} classes under
 * {@code com.automation.executor}, found by a classpath scan (no Spring context needed). Each
 * executor's {@code supports()} is a pure string comparison, so it is probed on an instance
 * created without running the constructor. The frontend mirrors these names in
 * {@code src/types/index.ts} ({@code BLOCK_TYPES}), guarded by {@code blockTypes.test.ts}.
 *
 * <p>Fails when an executor is added without maintaining
 * {@link BlockTypesIndexSection#BLOCK_TYPES} (assistant context + {@code GET /system/capabilities})
 * or {@link AssistantToolValidator#KNOWN_BLOCK_TYPES}, and when either list drifts from
 * {@link BlockTypeRegistry} — the set the save-time block-type check uses. The assistant's block
 * specifications ({@link BlockSpecs}, {@code fetch block-spec}) must cover the same types.
 */
class BlockTypeListsGuardTest {

    private static final String EXECUTOR_PACKAGE = "com.automation.executor";

    @Test
    void everyExecutorIsListedExactlyOnce_inBlockTypesIndexSection() {
        assertListMatchesExecutors("BlockTypesIndexSection.BLOCK_TYPES", indexSectionTypes());
    }

    @Test
    void everyExecutorIsListedExactlyOnce_inAssistantToolValidator() {
        assertListMatchesExecutors("AssistantToolValidator.KNOWN_BLOCK_TYPES",
                new TreeSet<>(AssistantToolValidator.KNOWN_BLOCK_TYPES));
    }

    /** The registry is what the save-time check uses (TODO 89dd9e34) — the hand list must match it. */
    @Test
    void assistantListMatchesTheBlockTypeRegistry() {
        List<BlockExecutor> executors = new java.util.ArrayList<>();
        for (Class<?> executorClass : executorClasses()) {
            executors.add((BlockExecutor) Mockito.mock(executorClass,
                    withSettings().defaultAnswer(CALLS_REAL_METHODS)));
        }

        assertThat(new BlockTypeRegistry(executors).knownTypes())
                .as("BlockTypeRegistry vs. AssistantToolValidator.KNOWN_BLOCK_TYPES")
                .containsExactlyElementsOf(new TreeSet<>(AssistantToolValidator.KNOWN_BLOCK_TYPES));
    }

    @Test
    void bothListsAgree() {
        assertThat(new TreeSet<>(AssistantToolValidator.KNOWN_BLOCK_TYPES))
                .as("validator list vs. index-section list")
                .containsExactlyElementsOf(indexSectionTypes());
    }

    /**
     * {@code <fetch type="block-spec" id="…"/>} knows every listed type (TODO 321b414e): four types
     * had no specification, and the assistant got "Unknown block type" for them.
     */
    @Test
    void everyListedTypeHasABlockSpec() {
        assertThat(new TreeSet<>(BlockSpecs.types()))
                .as("BlockSpecs vs. BlockTypesIndexSection.BLOCK_TYPES")
                .containsExactlyElementsOf(indexSectionTypes());
        DocsFetchHandler docs = new DocsFetchHandler();
        for (String type : indexSectionTypes()) {
            assertThat(docs.formatBlockSpec(type)).as("block-spec of %s", type)
                    .startsWith("**Block type: " + type + "**")
                    .contains("Required config:");
        }
    }

    @Test
    void indexSectionHasNoDuplicatesAndNoBlankSummaries() {
        List<String> types = BlockTypesIndexSection.BLOCK_TYPES.stream()
                .map(BlockTypesIndexSection.BlockTypeEntry::type).toList();
        assertThat(types).doesNotHaveDuplicates();
        assertThat(BlockTypesIndexSection.BLOCK_TYPES)
                .allSatisfy(e -> assertThat(e.summary()).isNotBlank());
    }

    private static void assertListMatchesExecutors(String listName, Set<String> listed) {
        Map<Class<?>, Set<String>> supportedByExecutor = new LinkedHashMap<>();
        for (Class<?> executorClass : executorClasses()) {
            Set<String> supported = new TreeSet<>();
            BlockExecutor probe = (BlockExecutor) Mockito.mock(executorClass,
                    withSettings().defaultAnswer(CALLS_REAL_METHODS));
            for (String type : listed) {
                if (probe.supports(type)) supported.add(type);
            }
            supportedByExecutor.put(executorClass, supported);
        }

        List<String> problems = new java.util.ArrayList<>();
        supportedByExecutor.forEach((cls, supported) -> {
            if (supported.isEmpty()) {
                problems.add(cls.getSimpleName() + " supports none of the types in " + listName
                        + " — add its supports() name to " + listName
                        + " (and to AssistantToolValidator.KNOWN_BLOCK_TYPES / frontend BLOCK_TYPES).");
            } else if (supported.size() > 1) {
                problems.add(cls.getSimpleName() + " supports several listed types " + supported);
            }
        });
        Set<String> covered = new LinkedHashSet<>();
        supportedByExecutor.values().forEach(covered::addAll);
        for (String type : listed) {
            if (!covered.contains(type)) {
                problems.add("'" + type + "' is listed in " + listName + " but no BlockExecutor supports it.");
            }
        }
        assertThat(problems).as(listName + " vs. registered BlockExecutors").isEmpty();
        assertThat(listed).as("size of " + listName).hasSize(supportedByExecutor.size());
    }

    private static Set<String> indexSectionTypes() {
        Set<String> types = new TreeSet<>();
        BlockTypesIndexSection.BLOCK_TYPES.forEach(e -> types.add(e.type()));
        return types;
    }

    private static List<Class<?>> executorClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BlockExecutor.class));
        List<Class<?>> classes = new java.util.ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(EXECUTOR_PACKAGE)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(classes).as("BlockExecutor implementations under " + EXECUTOR_PACKAGE).isNotEmpty();
        return classes;
    }
}

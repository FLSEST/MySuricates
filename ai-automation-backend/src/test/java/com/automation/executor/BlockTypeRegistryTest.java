package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

/**
 * The registry is the single source of truth for the block types a workflow may contain
 * (TODO 89dd9e34): it is derived from the registered {@link BlockExecutor} beans, never from a
 * hand-maintained list.
 */
class BlockTypeRegistryTest {

    private static final String EXECUTOR_PACKAGE = "com.automation.executor";

    @Test
    void knownTypes_collectsEveryExecutorsTypes_sortedAndDeduplicated() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(
                executorFor("shell"), executorFor("simple"), executorFor("shell")));

        assertThat(registry.knownTypes()).containsExactly("shell", "simple");
    }

    @Test
    void isKnown_onlyForRegisteredTypes() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("shell")));

        assertThat(registry.isKnown("shell")).isTrue();
        assertThat(registry.isKnown("frobnicate")).isFalse();
        assertThat(registry.isKnown("")).isFalse();
        assertThat(registry.isKnown(null)).isFalse();
    }

    @Test
    void knownTypesAsText_listsTypesForErrorMessages() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("simple"), executorFor("shell")));

        assertThat(registry.knownTypesAsText()).isEqualTo("shell, simple");
    }

    @Test
    void knownTypes_isImmutable() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("shell")));

        assertThat(registry.knownTypes()).isUnmodifiable();
    }

    /** Every executor on the classpath contributes exactly one type — no executor is unreachable. */
    @Test
    void registryBuiltFromAllExecutorsOnTheClasspath_hasOneTypePerExecutor() {
        List<BlockExecutor> executors = scannedExecutors();
        BlockTypeRegistry registry = new BlockTypeRegistry(executors);

        assertThat(registry.knownTypes())
                .as("one block type per BlockExecutor under " + EXECUTOR_PACKAGE)
                .hasSize(executors.size())
                .contains("simple", "shell", "switch", "foreach", "compress-ai");
        assertThat(registry.isKnown("frobnicate")).isFalse();
    }

    private static BlockExecutor executorFor(String type) {
        return new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                return null;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of(type);
            }
        };
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
        assertThat(executors).as("BlockExecutor implementations under " + EXECUTOR_PACKAGE).isNotEmpty();
        return executors;
    }
}

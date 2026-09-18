package com.automation.executor;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The block types the platform can execute, collected from the registered {@link BlockExecutor}
 * beans — never a hand-maintained list.
 *
 * <p>Used by {@code BlockTypeValidator} to refuse unknown types when a workflow definition is
 * saved (TODO 89dd9e34), so a typo fails at save time instead of mid-run.
 */
@Component
public class BlockTypeRegistry {

    private final List<BlockExecutor> executors;
    private volatile Set<String> knownTypes;

    /**
     * The executor list is injected lazily and read on first use: an executor may itself depend on
     * a service that (through {@code WorkflowMutationGateway}) needs this registry — resolving the
     * beans in the constructor would close that circle at startup.
     */
    public BlockTypeRegistry(@Lazy List<BlockExecutor> executors) {
        this.executors = executors;
    }

    public boolean isKnown(String blockType) {
        return blockType != null && knownTypes().contains(blockType);
    }

    /** All executable block types, sorted. */
    public Set<String> knownTypes() {
        Set<String> snapshot = knownTypes;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = knownTypes;
                if (snapshot == null) {
                    Set<String> collected = new TreeSet<>();
                    for (BlockExecutor executor : executors) {
                        Collection<String> types = executor.supportedTypes();
                        if (types != null) {
                            collected.addAll(types);
                        }
                    }
                    snapshot = Collections.unmodifiableSet(collected);
                    knownTypes = snapshot;
                }
            }
        }
        return snapshot;
    }

    /** The known types for error messages: {@code "agentic, approval, …"}. */
    public String knownTypesAsText() {
        return String.join(", ", knownTypes());
    }
}

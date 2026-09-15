package com.automation.executor;

import com.automation.entity.StepResult;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Name → facts of the finished top-level steps of a run, for compression sources. Written by
 * {@code BlockStepRunner} next to {@code recordOutput} and rebuilt on resume. A name recorded twice
 * is ambiguous (duplicate block names in one workflow).
 */
public final class SourceStepIndex {

    private final Map<String, SourceStep> byName = new ConcurrentHashMap<>();
    private final Set<String> ambiguous = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SourceStep> last = new AtomicReference<>();

    public void record(String blockName, StepResult step) {
        String name = blockName == null || blockName.isBlank() ? null : blockName;
        SourceStep entry = SourceStep.of(name, step);
        last.set(entry);
        if (name != null && byName.putIfAbsent(name, entry) != null) {
            ambiguous.add(name);
        }
    }

    public Optional<SourceStep> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    public boolean isAmbiguous(String name) {
        return ambiguous.contains(name);
    }

    public Optional<SourceStep> last() {
        return Optional.ofNullable(last.get());
    }
}

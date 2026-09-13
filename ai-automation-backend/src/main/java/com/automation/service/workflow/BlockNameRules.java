package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.executor.ExecutionContext;

import java.util.List;
import java.util.Set;

/**
 * Rules for block names that the executor cannot disambiguate at runtime.
 *
 * <p>{@code previous_output} is the pseudo-name the taint bookkeeping uses for the implicit
 * previous-output variable ({@link ExecutionContext#PREVIOUS_OUTPUT_NAME}). A block carrying that
 * name would share the entry: after a resume or in a sub-workflow its output would be treated as
 * trusted and land unescaped in shell commands. Rejecting the name at save time keeps the flat
 * taint export unambiguous (TODO b80c9855).
 */
public final class BlockNameRules {

    static final Set<String> RESERVED = Set.of(ExecutionContext.PREVIOUS_OUTPUT_NAME);

    private BlockNameRules() {
    }

    /** @throws IllegalArgumentException when a block uses a reserved name */
    public static void rejectReservedNames(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return;
        }
        for (BlockDefinition block : blocks) {
            String name = block == null || block.getName() == null ? null : block.getName().trim();
            if (name != null && RESERVED.contains(name)) {
                throw new IllegalArgumentException("Block name '" + name + "' is reserved (implicit previous-output "
                        + "variable); choose another name");
            }
        }
    }
}

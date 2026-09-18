package com.automation.executor.blocks;

import com.automation.entity.GateStatus;
import com.automation.service.todo.GateStatusOutputParser;

/**
 * The {@code gate_status:} header a {@code checked_command} shell block puts in front of its own
 * output, derived from the exit code. The gate belongs to that step — it is evaluated exactly once
 * and must never be marked {@code derivedOutput} (see {@code DerivedOutputProducersTest}).
 *
 * <p>The header is only prepended when it is <em>worse</em> than a verdict the command already
 * reported itself. {@code GateStatusOutputParser} takes the first marker in the output, so a
 * synthetic {@code GREEN} in front of a script's own {@code gate_status: RED} used to silently
 * downgrade the run — exactly the documented pattern of a check script that reports RED and still
 * exits 0 so the gate gets evaluated (TODO 52826011).
 */
final class CheckedCommandGate {

    private CheckedCommandGate() {
    }

    /** @return the output with the synthetic gate header, or unchanged if the command's own gate wins */
    static String decorate(String output, int exitCode, Object rawCategory) {
        GateStatus fromExit = exitCode == 0 ? GateStatus.GREEN : GateStatus.RED;
        GateStatus own = GateStatusOutputParser.parse(output)
                .map(GateStatusOutputParser.ParsedGateStatus::status)
                .orElse(null);
        if (own != null && own.ordinal() >= fromExit.ordinal()) {
            return output;
        }
        String category = String.valueOf(rawCategory == null ? "build-check" : rawCategory)
                .replaceAll("[^a-zA-Z0-9_-]", "-");
        return "gate_status: " + fromExit.name()
                + "\ngate_category: " + category
                + "\ngate_description: Command exit " + exitCode + "\n\n" + output;
    }
}

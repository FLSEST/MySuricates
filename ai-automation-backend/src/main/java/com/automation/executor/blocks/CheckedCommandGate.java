package com.automation.executor.blocks;

import com.automation.entity.GateStatus;
import com.automation.service.runoutput.CommandExitDescription;
import com.automation.service.runoutput.GateStatusOutputParser;

/**
 * The {@code gate_status:} header a {@code checked_command} shell block puts in front of its own
 * output, derived from the exit code. The gate belongs to that step — it is evaluated exactly once
 * and must never be marked {@code derivedOutput} (see {@code DerivedOutputProducersTest}).
 *
 * <p>The {@code gate_status} line is only prepended when it is <em>worse</em> than a verdict the
 * command already reported itself. {@code GateStatusOutputParser} takes the first marker in the
 * output, so a synthetic {@code GREEN} in front of a script's own {@code gate_status: RED} used to
 * silently downgrade the run — exactly the documented pattern of a check script that reports RED
 * and still exits 0 so the gate gets evaluated (TODO 52826011).
 *
 * <p>When the command's own verdict wins, {@code gate_category} and {@code gate_description} are
 * still filled in from the block wherever the output leaves them empty: a bare {@code gate_status:}
 * line — one that travelled in with text the command echoed, say — must not leave the step's gate
 * without a category.
 */
final class CheckedCommandGate {

    private CheckedCommandGate() {
    }

    /** @return the output with the gate header the block contributes on top of the command's own */
    static String decorate(String output, int exitCode, Object rawCategory) {
        GateStatus fromExit = exitCode == 0 ? GateStatus.GREEN : GateStatus.RED;
        GateStatusOutputParser.ParsedGateStatus own = GateStatusOutputParser.parse(output).orElse(null);
        String category = String.valueOf(rawCategory == null ? "build-check" : rawCategory)
                .replaceAll("[^a-zA-Z0-9_-]", "-");
        String description = CommandExitDescription.of(exitCode);

        StringBuilder header = new StringBuilder();
        if (own == null || own.status().ordinal() < fromExit.ordinal()) {
            header.append("gate_status: ").append(fromExit.name()).append("\n");
            own = null; // the block's verdict wins, so its category/description do too
        }
        if (own == null || own.category() == null) {
            header.append("gate_category: ").append(category).append("\n");
        }
        if (own == null || own.description() == null) {
            header.append("gate_description: ").append(description).append("\n");
        }
        return header.isEmpty() ? output : header.append("\n").append(output).toString();
    }
}

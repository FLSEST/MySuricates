package com.automation.executor.blocks;

import com.automation.entity.GateStatus;
import com.automation.service.todo.GateStatusOutputParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckedCommandGateTest {

    @Test
    void outputWithoutVerdict_getsFullHeaderFromExitCode() {
        assertThat(CheckedCommandGate.decorate("ok", 0, null))
                .isEqualTo("gate_status: GREEN\ngate_category: build-check\ngate_description: Command exit 0\n\nok");
        assertThat(CheckedCommandGate.decorate("boom", 3, "tests"))
                .startsWith("gate_status: RED\ngate_category: tests\ngate_description: Command exit 3\n\n");
    }

    @Test
    void category_isSanitized() {
        assertThat(CheckedCommandGate.decorate("", 0, "a b/c;rm"))
                .contains("gate_category: a-b-c-rm\n");
    }

    @Test
    void ownRedVerdict_isNotDowngradedBySuccessfulExit() {
        String own = "gate_status: RED\ngate_category: lint\ngate_description: 3 errors\n";

        String decorated = CheckedCommandGate.decorate(own, 0, "build");

        assertThat(decorated).isEqualTo(own);
        assertThat(GateStatusOutputParser.parse(decorated).orElseThrow().status()).isEqualTo(GateStatus.RED);
    }

    @Test
    void failingExit_overridesOwnGreenVerdict_withBlockCategory() {
        String decorated = CheckedCommandGate.decorate("gate_status: GREEN\ngate_category: own\n", 1, "build");

        var parsed = GateStatusOutputParser.parse(decorated).orElseThrow();
        assertThat(parsed.status()).isEqualTo(GateStatus.RED);
        assertThat(parsed.category()).isEqualTo("build");
        assertThat(parsed.description()).isEqualTo("Command exit 1");
    }

    @Test
    void ownVerdictWins_butMissingCategoryAndDescriptionAreFilledIn() {
        String decorated = CheckedCommandGate.decorate("gate_status: YELLOW\n", 0, "check");

        assertThat(decorated).isEqualTo("gate_category: check\ngate_description: Command exit 0\n\ngate_status: YELLOW\n");
        var parsed = GateStatusOutputParser.parse(decorated).orElseThrow();
        assertThat(parsed.status()).isEqualTo(GateStatus.YELLOW);
        assertThat(parsed.category()).isEqualTo("check");
    }

    @Test
    void equalVerdict_keepsCommandsOwnFields() {
        String own = "gate_status: RED\ngate_category: own\ngate_description: mine\n";

        assertThat(CheckedCommandGate.decorate(own, 2, "block")).isEqualTo(own);
    }
}

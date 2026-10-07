package com.automation.exception;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizedStateExceptionTest {

    @Test
    void should_remainAnIllegalStateException_forExistingCallers() {
        var ex = new LocalizedStateException(ErrorMessage.CHAIN_RUN_ACTIVE);

        assertThat(ex).isInstanceOf(IllegalStateException.class).isInstanceOf(LocalizedError.class);
    }

    @Test
    void should_useTemplateAsMessage_withEmptyParams_when_noParamsGiven() {
        var ex = new LocalizedStateException(ErrorMessage.TEST_INSTANCE_NOT_RUNNING);

        assertThat(ex.getMessage()).isEqualTo("Test instance is not running or not healthy. Start it first.");
        assertThat(ex.errorMessage()).isEqualTo(ErrorMessage.TEST_INSTANCE_NOT_RUNNING);
        assertThat(ex.errorMessage().getCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
        assertThat(ex.params()).isEmpty();
    }

    @Test
    void should_fillPlaceholders_andCopyParams_when_paramsGiven() {
        Map<String, String> params = new HashMap<>(Map.of("target", "stable"));
        var ex = new LocalizedStateException(ErrorMessage.SYNC_TARGET_NOT_CONFIGURED, params);
        params.clear();

        assertThat(ex.getMessage()).isEqualTo("Sync target 'stable' is not configured on this instance");
        assertThat(ex.params()).containsExactlyEntriesOf(Map.of("target", "stable"));
    }
}

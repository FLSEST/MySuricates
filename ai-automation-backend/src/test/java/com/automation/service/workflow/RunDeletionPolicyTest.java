package com.automation.service.workflow;

import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunDeletionPolicyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RunDeletionPolicy.class);

    @Test
    void allowsDeletion_whenPropertyIsUnset() {
        runner.run(ctx -> {
            RunDeletionPolicy policy = ctx.getBean(RunDeletionPolicy.class);
            assertThat(policy.isAllowed()).isTrue();
            assertThatCode(policy::requireAllowed).doesNotThrowAnyException();
        });
    }

    @Test
    void refusesDeletion_withForbiddenCode_whenPropertyIsFalse() {
        runner.withPropertyValues("automation.executions.allow-delete=false").run(ctx -> {
            RunDeletionPolicy policy = ctx.getBean(RunDeletionPolicy.class);
            assertThat(policy.isAllowed()).isFalse();
            assertThatThrownBy(policy::requireAllowed)
                    .isInstanceOf(HardeningException.class)
                    .satisfies(e -> assertThat(((HardeningException) e).getCode())
                            .isEqualTo(ErrorCode.RUN_DELETION_DISABLED));
            assertThat(ErrorCode.RUN_DELETION_DISABLED.getHttpStatus().value()).isEqualTo(403);
        });
    }

    /** 8dbd7e10: with deletion off, the retention may only grow — 0 (off) counts as the longest. */
    @Test
    void retentionChange_whileDeletionIsOff_onlyExtends() {
        RunDeletionPolicy locked = new RunDeletionPolicy(false);

        assertThatCode(() -> locked.requireRetentionChangeAllowed(90, 90)).doesNotThrowAnyException();
        assertThatCode(() -> locked.requireRetentionChangeAllowed(90, 365)).doesNotThrowAnyException();
        assertThatCode(() -> locked.requireRetentionChangeAllowed(90, 0)).doesNotThrowAnyException();
        assertThatCode(() -> locked.requireRetentionChangeAllowed(0, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> locked.requireRetentionChangeAllowed(90, 30))
                .isInstanceOf(HardeningException.class)
                .extracting(e -> ((HardeningException) e).getCode())
                .isEqualTo(ErrorCode.RUN_DELETION_DISABLED);
        assertThatThrownBy(() -> locked.requireRetentionChangeAllowed(0, 730)).isInstanceOf(HardeningException.class);
    }

    @Test
    void retentionChange_whileDeletionIsAllowed_isFree() {
        assertThatCode(() -> new RunDeletionPolicy(true).requireRetentionChangeAllowed(90, 1)).doesNotThrowAnyException();
    }
}

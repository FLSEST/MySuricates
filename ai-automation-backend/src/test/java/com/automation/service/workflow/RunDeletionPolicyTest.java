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
}

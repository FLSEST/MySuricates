package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class BlockExecutorTest {

    // --- static cliTypeFor() tests ---

    @Test
    void cliTypeFor_anyBlockType_returnsClaude() {
        assertThat(BlockExecutor.cliTypeFor("simple")).isEqualTo("claude");
        assertThat(BlockExecutor.cliTypeFor("shell")).isEqualTo("claude");
        assertThat(BlockExecutor.cliTypeFor("agentic")).isEqualTo("claude");
        assertThat(BlockExecutor.cliTypeFor("foreach")).isEqualTo("claude");
    }

    @Test
    void cliTypeFor_nullBlockType_returnsClaude() {
        assertThat(BlockExecutor.cliTypeFor(null)).isEqualTo("claude");
    }

    @Test
    void cliTypeFor_emptyBlockType_returnsClaude() {
        assertThat(BlockExecutor.cliTypeFor("")).isEqualTo("claude");
    }

    @Test
    void cliTypeFor_unknownBlockType_returnsClaude() {
        assertThat(BlockExecutor.cliTypeFor("totally-made-up-block-type")).isEqualTo("claude");
    }

    // --- interface contract tests via mock implementation ---

    @Test
    void execute_mockImplementation_returnsExpectedStepResult() {
        StepResult expectedResult = Mockito.mock(StepResult.class);
        BlockDefinition block = Mockito.mock(BlockDefinition.class);
        ExecutionContext context = Mockito.mock(ExecutionContext.class);

        BlockExecutor executor = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition b, ExecutionContext ctx) {
                return expectedResult;
            }

            @Override
            public boolean supports(String blockType) {
                return "test-block".equals(blockType);
            }
        };

        StepResult actual = executor.execute(block, context);

        assertThat(actual).isSameAs(expectedResult);
    }

    @Test
    void supports_mockImplementation_returnsTrueForMatchingType() {
        BlockExecutor executor = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition b, ExecutionContext ctx) {
                return null;
            }

            @Override
            public boolean supports(String blockType) {
                return "my-block".equals(blockType);
            }
        };

        assertThat(executor.supports("my-block")).isTrue();
        assertThat(executor.supports("other-block")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    void execute_receivesPassedBlockAndContext() {
        BlockDefinition block = Mockito.mock(BlockDefinition.class);
        ExecutionContext context = Mockito.mock(ExecutionContext.class);
        BlockDefinition[] capturedBlock = new BlockDefinition[1];
        ExecutionContext[] capturedContext = new ExecutionContext[1];

        BlockExecutor executor = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition b, ExecutionContext ctx) {
                capturedBlock[0] = b;
                capturedContext[0] = ctx;
                return null;
            }

            @Override
            public boolean supports(String blockType) {
                return false;
            }
        };

        executor.execute(block, context);

        assertThat(capturedBlock[0]).isSameAs(block);
        assertThat(capturedContext[0]).isSameAs(context);
    }
}

package com.automation.service.cli;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostExecutionRequestTest {

    private static final String VALID_PATH = "/workspace/project";
    private static final String VALID_COMMAND = "Review the code";

    @Nested
    class ProjectPathValidationTests {

        @Test
        void should_throwException_when_projectPathIsNull() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath(null)
                    .command(VALID_COMMAND)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("projectPath is required");
        }

        @Test
        void should_throwException_when_projectPathIsEmpty() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath("")
                    .command(VALID_COMMAND)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("projectPath is required");
        }

        @Test
        void should_throwException_when_projectPathIsBlank() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath("   ")
                    .command(VALID_COMMAND)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("projectPath is required");
        }

        @Test
        void should_throwException_when_projectPathIsTab() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath("\t")
                    .command(VALID_COMMAND)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("projectPath is required");
        }
    }

    @Nested
    class CommandOrBlockValidationTests {

        @Test
        void should_throwException_when_neitherCommandNorBlockProvided() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Either command or block must be provided");
        }

        @Test
        void should_throwException_when_commandIsNullAndBlockIsNull() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(null)
                    .block(null)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Either command or block must be provided");
        }

        @Test
        void should_throwException_when_commandIsEmptyAndBlockIsNull() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command("")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Either command or block must be provided");
        }

        @Test
        void should_throwException_when_commandIsBlankAndBlockIsNull() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command("   ")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Either command or block must be provided");
        }

        @Test
        void should_succeed_when_commandIsProvided() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .build();

            assertThat(request.command()).isEqualTo(VALID_COMMAND);
        }

        @Test
        void should_succeed_when_blockIsProvided() {
            var block = new BlockDefinition();
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .block(block)
                    .build();

            assertThat(request.block()).isSameAs(block);
            assertThat(request.command()).isNull();
        }

        @Test
        void should_succeed_when_bothCommandAndBlockProvided() {
            var block = new BlockDefinition();
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .block(block)
                    .build();

            assertThat(request.command()).isEqualTo(VALID_COMMAND);
            assertThat(request.block()).isSameAs(block);
        }
    }

    @Nested
    class ShellBypassTests {

        @Test
        void should_succeed_when_shellIsTrueWithoutCommandOrBlock() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .shell(true)
                    .build();

            assertThat(request.shell()).isTrue();
            assertThat(request.command()).isNull();
            assertThat(request.block()).isNull();
        }

        @Test
        void should_succeed_when_shellIsTrueWithCommand() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .shell(true)
                    .command("ls -la")
                    .build();

            assertThat(request.shell()).isTrue();
            assertThat(request.command()).isEqualTo("ls -la");
        }

        @Test
        void should_throwException_when_shellIsFalseWithoutCommandOrBlock() {
            assertThatThrownBy(() -> HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .shell(false)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Either command or block must be provided");
        }
    }

    @Nested
    class BuilderDefaultsTests {

        @Test
        void should_defaultShellToFalse() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .build();

            assertThat(request.shell()).isFalse();
        }

        @Test
        void should_defaultPriorityToFalse() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .build();

            assertThat(request.priority()).isFalse();
        }

        @Test
        void should_defaultOptionalFieldsToNull() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .build();

            assertThat(request.block()).isNull();
            assertThat(request.stepIndex()).isNull();
            assertThat(request.model()).isNull();
            assertThat(request.systemPrompt()).isNull();
            assertThat(request.timeoutSeconds()).isNull();
            assertThat(request.providerId()).isNull();
        }
    }

    @Nested
    class AllFieldsViaBuilderTests {

        @Test
        void should_setAllFieldsViaBuilder() {
            var block = new BlockDefinition();
            var request = HostExecutionRequest.builder()
                    .projectPath("/home/user/project")
                    .command("Analyze this code")
                    .block(block)
                    .stepIndex(3)
                    .model("claude-opus-4-6")
                    .systemPrompt("You are a senior engineer")
                    .timeoutSeconds(600)
                    .providerId("CLAUDE")
                    .shell(true)
                    .priority(true)
                    .build();

            assertThat(request.projectPath()).isEqualTo("/home/user/project");
            assertThat(request.command()).isEqualTo("Analyze this code");
            assertThat(request.block()).isSameAs(block);
            assertThat(request.stepIndex()).isEqualTo(3);
            assertThat(request.model()).isEqualTo("claude-opus-4-6");
            assertThat(request.systemPrompt()).isEqualTo("You are a senior engineer");
            assertThat(request.timeoutSeconds()).isEqualTo(600);
            assertThat(request.providerId()).isEqualTo("CLAUDE");
            assertThat(request.shell()).isTrue();
            assertThat(request.priority()).isTrue();
        }
    }

    @Nested
    class RecordEqualityTests {

        @Test
        void should_beEqual_when_sameValues() {
            var r1 = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .model("claude-sonnet-4-6")
                    .build();
            var r2 = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .model("claude-sonnet-4-6")
                    .build();

            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        void should_notBeEqual_when_differentValues() {
            var r1 = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command("command A")
                    .build();
            var r2 = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command("command B")
                    .build();

            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        void should_haveReadableToString() {
            var request = HostExecutionRequest.builder()
                    .projectPath(VALID_PATH)
                    .command(VALID_COMMAND)
                    .build();

            assertThat(request.toString()).contains("projectPath");
            assertThat(request.toString()).contains(VALID_PATH);
        }
    }
}

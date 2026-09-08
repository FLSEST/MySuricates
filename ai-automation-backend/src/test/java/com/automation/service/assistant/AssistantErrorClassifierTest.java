package com.automation.service.assistant;

import com.automation.config.HostClaudeConfig;
import com.automation.exception.WorkflowExecutionException;
import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantErrorClassifierTest {

    private AssistantErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setAssistantChatTimeoutSeconds(240);
        classifier = new AssistantErrorClassifier(config);
    }

    @Test
    void classify_ioTimeout_isTimeoutWithConfiguredSeconds() {
        var kind = classifier.classify(new IOException("Command timed out after 240s"));

        assertThat(kind.code()).isEqualTo("TIMEOUT");
        assertThat(kind.userMessage()).contains("240 seconds");
    }

    @Test
    void classify_timeoutWrappedInWorkflowExecutionException_isUnwrapped() {
        var wrapped = new WorkflowExecutionException("failed", new IOException("timed out"));

        assertThat(classifier.classify(wrapped).code()).isEqualTo("TIMEOUT");
    }

    @Test
    void classify_ioCauseOfPlainRuntimeException_isUnwrappedOneLevel() {
        var wrapped = new RuntimeException("outer", new IOException("timed out"));

        assertThat(classifier.classify(wrapped).code()).isEqualTo("TIMEOUT");
    }

    @Test
    void classify_sqlTransientConnection_isPoolExhausted() {
        assertThat(classifier.classify(new SQLTransientConnectionException("busy")).code())
                .isEqualTo("POOL_EXHAUSTED");
    }

    @Test
    void classify_connectionNotAvailableMessage_isPoolExhausted() {
        assertThat(classifier.classify(new RuntimeException("Connection is not available, request timed out")).code())
                .isEqualTo("POOL_EXHAUSTED");
    }

    @Test
    void classify_jsonProcessingException_isToolCallMalformed() {
        assertThat(classifier.classify(new JsonParseException(null, "boom")).code())
                .isEqualTo("TOOL_CALL_MALFORMED");
    }

    @Test
    void classify_jsonErrorMessageWithoutJacksonType_isToolCallMalformed() {
        assertThat(classifier.classify(new RuntimeException("Unexpected character ('，')")).code())
                .isEqualTo("TOOL_CALL_MALFORMED");
    }

    @Test
    void classify_apiKeyMessage_isProviderUnavailable() {
        assertThat(classifier.classify(new RuntimeException("Invalid API key")).code())
                .isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void classify_cliNonZeroExit_surfacesExitCodeExcerpt() {
        var kind = classifier.classify(new WorkflowExecutionException("Assistant CLI failed (exit 1): bad config"));

        assertThat(kind.code()).isEqualTo("CLI_NON_ZERO_EXIT");
        assertThat(kind.userMessage()).contains("(exit 1): bad config");
    }

    @Test
    void classify_anythingElse_isUnknown() {
        var kind = classifier.classify(new IllegalStateException("whatever"));

        assertThat(kind.code()).isEqualTo("UNKNOWN");
        assertThat(kind.userMessage()).contains("server log");
    }

    @Test
    void classify_nullMessage_doesNotThrow() {
        assertThat(classifier.classify(new RuntimeException((String) null)).code()).isEqualTo("UNKNOWN");
    }
}

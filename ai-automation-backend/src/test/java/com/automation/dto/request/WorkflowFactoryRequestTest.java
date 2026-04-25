package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowFactoryRequestTest {

    @Test
    void should_storeAllFields_when_constructedWithFullValues() {
        Map<String, Object> inputs = Map.of("key", "value");
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Build a chatbot",
                "A simple chatbot workflow",
                "ai",
                "project-123",
                inputs,
                true,
                true,
                5,
                "ANTHROPIC"
        );

        assertThat(request.topic()).isEqualTo("Build a chatbot");
        assertThat(request.description()).isEqualTo("A simple chatbot workflow");
        assertThat(request.category()).isEqualTo("ai");
        assertThat(request.projectId()).isEqualTo("project-123");
        assertThat(request.testInputs()).isEqualTo(inputs);
        assertThat(request.autoPromote()).isTrue();
        assertThat(request.autoFix()).isTrue();
        assertThat(request.maxRetries()).isEqualTo(5);
        assertThat(request.provider()).isEqualTo("ANTHROPIC");
    }

    @Test
    void should_defaultMaxRetriesToTwo_when_maxRetriesIsZero() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "topic", null, null, null, Map.of(), false, false, 0, null
        );

        assertThat(request.maxRetries()).isEqualTo(2);
    }

    @Test
    void should_defaultMaxRetriesToTwo_when_maxRetriesIsNegative() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "topic", null, null, null, Map.of(), false, false, -3, null
        );

        assertThat(request.maxRetries()).isEqualTo(2);
    }

    @Test
    void should_keepProvidedMaxRetries_when_maxRetriesIsPositive() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "topic", null, null, null, Map.of(), false, false, 7, null
        );

        assertThat(request.maxRetries()).isEqualTo(7);
    }

    @Test
    void should_defaultTestInputsToEmptyMap_when_testInputsIsNull() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "topic", null, null, null, null, false, false, 1, null
        );

        assertThat(request.testInputs()).isNotNull().isEmpty();
    }

    @Test
    void should_keepProvidedTestInputs_when_testInputsIsNotNull() {
        Map<String, Object> inputs = Map.of("a", 1, "b", "two");
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "topic", null, null, null, inputs, false, false, 1, null
        );

        assertThat(request.testInputs()).isEqualTo(inputs);
    }

    @Test
    void should_allowNullableOptionalFields_when_onlyTopicIsProvided() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "topic", null, null, null, null, false, false, 2, null
        );

        assertThat(request.topic()).isEqualTo("topic");
        assertThat(request.description()).isNull();
        assertThat(request.category()).isNull();
        assertThat(request.projectId()).isNull();
        assertThat(request.provider()).isNull();
        assertThat(request.autoPromote()).isFalse();
        assertThat(request.autoFix()).isFalse();
    }

    @Test
    void should_beEqualAndHaveSameHashCode_when_fieldsAreIdentical() {
        Map<String, Object> inputs = Map.of("k", "v");
        WorkflowFactoryRequest a = new WorkflowFactoryRequest(
                "topic", "desc", "cat", "p1", inputs, true, false, 3, "ANTHROPIC"
        );
        WorkflowFactoryRequest b = new WorkflowFactoryRequest(
                "topic", "desc", "cat", "p1", inputs, true, false, 3, "ANTHROPIC"
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_topicDiffers() {
        WorkflowFactoryRequest a = new WorkflowFactoryRequest(
                "topic-A", null, null, null, Map.of(), false, false, 2, null
        );
        WorkflowFactoryRequest b = new WorkflowFactoryRequest(
                "topic-B", null, null, null, Map.of(), false, false, 2, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_includeFieldValuesInToString() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "my-topic", "desc", "cat", "p1", Map.of(), true, true, 4, "ANTHROPIC"
        );

        String s = request.toString();
        assertThat(s).contains("my-topic")
                .contains("desc")
                .contains("cat")
                .contains("p1")
                .contains("ANTHROPIC");
    }
}

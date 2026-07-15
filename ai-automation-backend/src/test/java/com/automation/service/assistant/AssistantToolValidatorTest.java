package com.automation.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantToolValidatorTest {

    private AssistantToolValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AssistantToolValidator(new ObjectMapper());
    }

    @Test
    void validate_invalidJson_returnsError() {
        Optional<String> error = validator.validate("create_workflow", "{not valid json");

        assertThat(error).isPresent();
        assertThat(error.get()).contains("JSON");
    }

    @Test
    void validate_createWorkflow_validBlocks_passes() {
        String payload = """
                {"projectId": "p1", "name": "Test",
                 "blocks": [
                   {"type": "shell", "config": {"command": "echo hi"}},
                   {"type": "simple", "config": {"command": "Fasse zusammen"}},
                   {"type": "agentic", "config": {"goal": "Fix the bug", "success_criteria": "tests green"}}
                 ]}""";

        assertThat(validator.validate("create_workflow", payload)).isEmpty();
    }

    @Test
    void validate_createWorkflow_unknownBlockType_returnsError() {
        String payload = """
                {"projectId": "p1", "name": "Test",
                 "blocks": [{"type": "quantum-magic", "config": {"command": "x"}}]}""";

        Optional<String> error = validator.validate("create_workflow", payload);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("quantum-magic");
    }

    @Test
    void validate_createWorkflow_blockWithoutType_returnsError() {
        String payload = """
                {"projectId": "p1", "name": "Test",
                 "blocks": [{"config": {"command": "x"}}]}""";

        assertThat(validator.validate("create_workflow", payload)).isPresent();
    }

    @Test
    void validate_createWorkflow_commandBlockWithoutCommand_returnsError() {
        String payload = """
                {"projectId": "p1", "name": "Test",
                 "blocks": [{"type": "shell", "config": {}}]}""";

        Optional<String> error = validator.validate("create_workflow", payload);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("command");
    }

    @Test
    void validate_createWorkflow_agenticWithoutGoal_returnsError() {
        String payload = """
                {"projectId": "p1", "name": "Test",
                 "blocks": [{"type": "agentic", "config": {"success_criteria": "done"}}]}""";

        Optional<String> error = validator.validate("create_workflow", payload);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("goal");
    }

    @Test
    void validate_updateWorkflow_blocksAreValidatedToo() {
        String payload = """
                {"workflowId": "w1",
                 "blocks": [{"type": "nonsense"}]}""";

        assertThat(validator.validate("update_workflow", payload)).isPresent();
    }

    @Test
    void validate_updateWorkflow_withoutBlocks_passes() {
        assertThat(validator.validate("update_workflow", "{\"workflowId\": \"w1\", \"name\": \"Neu\"}")).isEmpty();
    }

    @Test
    void validate_createChain_entryWithoutWorkflowId_returnsError() {
        String payload = """
                {"name": "Chain", "entries": [{"label": "Step 1"}]}""";

        Optional<String> error = validator.validate("create_chain", payload);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("workflowId");
    }

    @Test
    void validate_otherToolTypes_onlyRequireParseableJson() {
        assertThat(validator.validate("create_todo", "{\"title\": \"x\"}")).isEmpty();
        assertThat(validator.validate("start_workflow", "{\"workflowId\": \"w1\"}")).isEmpty();
    }
}

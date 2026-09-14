package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateWorkflowRequestTest {

    private static final String NAME = "My Workflow";
    private static final String DESCRIPTION = "A description";
    private static final String ICON = "icon.png";
    private static final List<BlockDefinition> BLOCKS = List.of(BlockDefinition.builder().type("simple").build());
    private static final String INSTRUCTIONS = "Do stuff";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final String PROVIDER = "ANTHROPIC";
    private static final Map<String, Object> METADATA = Map.of("key", "value");
    private static final Map<String, Object> DEFAULT_CONTEXT = Map.of("ctx", "val");

    @Test
    void should_storeAllFields_when_constructedWithChangeMessage() {
        var request = new UpdateWorkflowRequest(
                NAME, DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS,
                MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT, "Updated name");

        assertThat(request.name()).isEqualTo(NAME);
        assertThat(request.description()).isEqualTo(DESCRIPTION);
        assertThat(request.icon()).isEqualTo(ICON);
        assertThat(request.blocks()).isEqualTo(BLOCKS);
        assertThat(request.instructions()).isEqualTo(INSTRUCTIONS);
        assertThat(request.model()).isEqualTo(MODEL);
        assertThat(request.provider()).isEqualTo(PROVIDER);
        assertThat(request.metadata()).isEqualTo(METADATA);
        assertThat(request.defaultContext()).isEqualTo(DEFAULT_CONTEXT);
        assertThat(request.changeMessage()).isEqualTo("Updated name");
        assertThat(request.tags()).isNull();
        assertThat(request.category()).isNull();
        assertThat(request.effort()).isNull();
    }

    @Test
    void should_leaveChangeMessageNull_when_usingConvenienceConstructor() {
        var request = new UpdateWorkflowRequest(
                NAME, DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS,
                MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT);

        assertThat(request.name()).isEqualTo(NAME);
        assertThat(request.defaultContext()).isEqualTo(DEFAULT_CONTEXT);
        assertThat(request.changeMessage()).isNull();
    }

    @Test
    void should_notCarryAChangeSource_becauseTheServerDecidesWhoChangedAWorkflow() {
        assertThat(Arrays.stream(UpdateWorkflowRequest.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("changeSource");
    }

    @Test
    void should_acceptNullFields_when_fieldsAreOptional() {
        var request = new UpdateWorkflowRequest(null, null, null, null, null, null, null, null, null, null);

        assertThat(request.name()).isNull();
        assertThat(request.blocks()).isNull();
        assertThat(request.metadata()).isNull();
        assertThat(request.changeMessage()).isNull();
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new UpdateWorkflowRequest(NAME, DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS, MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT, "msg");
        var b = new UpdateWorkflowRequest(NAME, DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS, MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT, "msg");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_namesDiffer() {
        var a = new UpdateWorkflowRequest("Workflow A", DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS, MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT);
        var b = new UpdateWorkflowRequest("Workflow B", DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS, MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldNamesInToString() {
        var request = new UpdateWorkflowRequest(NAME, DESCRIPTION, ICON, BLOCKS, INSTRUCTIONS, MODEL, PROVIDER, METADATA, DEFAULT_CONTEXT, "Changed");

        assertThat(request.toString()).contains("name").contains("description").contains("changeMessage");
    }
}

package com.automation.mapper;

import com.automation.dto.response.StepResultResponse;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the MapStruct-generated {@code toStepResponse}: the block type/name live only in the step
 * metadata, but API clients (run detail page, Playwright/Cypress fixtures) read them top-level.
 */
class WorkflowMapperStepResponseTest {

    private final WorkflowMapper mapper = new WorkflowMapperImpl();

    @Test
    void liftsBlockTypeAndNameFromMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "shell");
        metadata.put("blockName", "Build");
        metadata.put("exitCode", 0);
        StepResult step = StepResult.builder()
                .id("s-1").stepIndex(2).status(StepStatus.COMPLETED).metadata(metadata).build();

        StepResultResponse response = mapper.toStepResponse(step);

        assertThat(response.blockType()).isEqualTo("shell");
        assertThat(response.blockName()).isEqualTo("Build");
        assertThat(response.stepIndex()).isEqualTo(2);
        assertThat(response.metadata()).containsEntry("exitCode", 0);
    }

    @Test
    void missingOrNonStringMetadataYieldsNull() {
        StepResult withoutMetadata = StepResult.builder().id("s-1").metadata(null).build();
        StepResult nonString = StepResult.builder().id("s-2").metadata(Map.of("blockType", 42)).build();

        assertThat(mapper.toStepResponse(withoutMetadata).blockType()).isNull();
        assertThat(mapper.toStepResponse(withoutMetadata).blockName()).isNull();
        assertThat(mapper.toStepResponse(nonString).blockType()).isNull();
    }
}

package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTest {

    @Test
    void computeBlockTypes_sortedAndDeduplicated() {
        Workflow workflow = Workflow.builder()
                .blocks(List.of(
                        BlockDefinition.builder().type("shell").build(),
                        BlockDefinition.builder().type("agentic").build(),
                        BlockDefinition.builder().type("simple").build(),
                        BlockDefinition.builder().type("shell").build()
                ))
                .build();

        workflow.computeBlockTypes();

        assertThat(workflow.getBlockTypes()).isEqualTo("agentic,shell,simple");
    }

    @Test
    void computeBlockTypes_singleBlock() {
        Workflow workflow = Workflow.builder()
                .blocks(List.of(BlockDefinition.builder().type("simple").build()))
                .build();

        workflow.computeBlockTypes();

        assertThat(workflow.getBlockTypes()).isEqualTo("simple");
    }

    @Test
    void computeBlockTypes_emptyBlocks_returnsEmpty() {
        Workflow workflow = Workflow.builder().blocks(List.of()).build();

        workflow.computeBlockTypes();

        assertThat(workflow.getBlockTypes()).isEmpty();
    }

    @Test
    void computeBlockTypes_nullBlocks_returnsEmpty() {
        Workflow workflow = Workflow.builder().blocks(null).build();

        workflow.computeBlockTypes();

        assertThat(workflow.getBlockTypes()).isEmpty();
    }

    @Test
    void computeBlockTypes_filtersNullAndEmptyTypes() {
        List<BlockDefinition> blocks = new ArrayList<>();
        blocks.add(BlockDefinition.builder().type("simple").build());
        blocks.add(BlockDefinition.builder().type(null).build());
        blocks.add(BlockDefinition.builder().type("").build());
        blocks.add(BlockDefinition.builder().type("shell").build());

        Workflow workflow = Workflow.builder().blocks(blocks).build();

        workflow.computeBlockTypes();

        assertThat(workflow.getBlockTypes()).isEqualTo("shell,simple");
    }

    @Test
    void computeBlockTypes_allBlockTypes() {
        Workflow workflow = Workflow.builder()
                .blocks(List.of(
                        BlockDefinition.builder().type("simple").build(),
                        BlockDefinition.builder().type("shell").build(),
                        BlockDefinition.builder().type("retry").build(),
                        BlockDefinition.builder().type("conditional").build(),
                        BlockDefinition.builder().type("forEach").build(),
                        BlockDefinition.builder().type("while").build(),
                        BlockDefinition.builder().type("switch").build(),
                        BlockDefinition.builder().type("agentic").build(),
                        BlockDefinition.builder().type("approval").build(),
                        BlockDefinition.builder().type("sub_workflow").build(),
                        BlockDefinition.builder().type("skill_capability").build(),
                        BlockDefinition.builder().type("human_input").build()
                ))
                .build();

        workflow.computeBlockTypes();

        assertThat(workflow.getBlockTypes())
                .isEqualTo("agentic,approval,conditional,forEach,human_input,retry,shell,simple,skill_capability,sub_workflow,switch,while");
    }

    @Test
    void computeBlockTypes_idempotent_callingTwiceGivesSameResult() {
        Workflow workflow = Workflow.builder()
                .blocks(List.of(
                        BlockDefinition.builder().type("shell").build(),
                        BlockDefinition.builder().type("simple").build()
                ))
                .build();

        workflow.computeBlockTypes();
        String first = workflow.getBlockTypes();
        workflow.computeBlockTypes();
        String second = workflow.getBlockTypes();

        assertThat(first).isEqualTo(second).isEqualTo("shell,simple");
    }
}

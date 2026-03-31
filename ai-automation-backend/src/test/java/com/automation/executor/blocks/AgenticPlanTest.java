package com.automation.executor.blocks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticPlanTest {

    @Test
    void parse_structuredResponse_extractsAllFields() {
        String response = """
                GOAL_STATUS: IN_PROGRESS
                RATIONALE: Need to fix the failing test
                ACTION: Run mvn test -Dtest=FooTest
                SUMMARY: Running the specific test to verify the fix
                """;

        AgenticPlan plan = AgenticPlan.parse(response);

        assertThat(plan.getGoalStatus()).isEqualTo("IN_PROGRESS");
        assertThat(plan.getRationale()).isEqualTo("Need to fix the failing test");
        assertThat(plan.getAction()).isEqualTo("Run mvn test -Dtest=FooTest");
        assertThat(plan.getSummary()).isEqualTo("Running the specific test to verify the fix");
    }

    @Test
    void parse_nullResponse_returnsImpossible() {
        AgenticPlan plan = AgenticPlan.parse(null);

        assertThat(plan.getGoalStatus()).isEqualTo("IMPOSSIBLE");
        assertThat(plan.isGoalComplete()).isTrue();
    }

    @Test
    void parse_emptyResponse_returnsImpossible() {
        AgenticPlan plan = AgenticPlan.parse("   ");

        assertThat(plan.getGoalStatus()).isEqualTo("IMPOSSIBLE");
        assertThat(plan.isGoalComplete()).isTrue();
    }

    @Test
    void parse_unstructuredResponse_treatsAsAction() {
        String response = "Just run the tests and fix everything";

        AgenticPlan plan = AgenticPlan.parse(response);

        assertThat(plan.getAction()).isEqualTo("Just run the tests and fix everything");
        assertThat(plan.getRationale()).contains("Unstructured");
    }

    @Test
    void parse_achievedGoal_marksComplete() {
        String response = """
                GOAL_STATUS: ACHIEVED
                RATIONALE: All tests pass
                ACTION:
                SUMMARY: Done
                """;

        AgenticPlan plan = AgenticPlan.parse(response);

        assertThat(plan.getGoalStatus()).isEqualTo("ACHIEVED");
        assertThat(plan.isGoalComplete()).isTrue();
    }

    @Test
    void parse_impossibleGoal_marksComplete() {
        String response = """
                GOAL_STATUS: IMPOSSIBLE
                RATIONALE: Cannot access the file
                ACTION:
                SUMMARY: Giving up
                """;

        AgenticPlan plan = AgenticPlan.parse(response);

        assertThat(plan.getGoalStatus()).isEqualTo("IMPOSSIBLE");
        assertThat(plan.isGoalComplete()).isTrue();
    }

    @Test
    void parse_inProgressGoal_notComplete() {
        String response = """
                GOAL_STATUS: IN_PROGRESS
                RATIONALE: Still working
                ACTION: Read the file
                SUMMARY: Reading
                """;

        AgenticPlan plan = AgenticPlan.parse(response);

        assertThat(plan.isGoalComplete()).isFalse();
    }

    @Test
    void isGoalComplete_variousStatuses() {
        assertThat(AgenticPlan.builder().goalStatus("ACHIEVED").build().isGoalComplete()).isTrue();
        assertThat(AgenticPlan.builder().goalStatus("achieved").build().isGoalComplete()).isTrue();
        assertThat(AgenticPlan.builder().goalStatus("IMPOSSIBLE").build().isGoalComplete()).isTrue();
        assertThat(AgenticPlan.builder().goalStatus("IN_PROGRESS").build().isGoalComplete()).isFalse();
        assertThat(AgenticPlan.builder().goalStatus("unknown").build().isGoalComplete()).isFalse();
    }

    @Test
    void parse_multilineAction_extractedCorrectly() {
        String response = """
                GOAL_STATUS: IN_PROGRESS
                RATIONALE: Need to run multiple commands
                ACTION: cd /project && mvn clean test
                SUMMARY: Building and testing
                """;

        AgenticPlan plan = AgenticPlan.parse(response);

        assertThat(plan.getAction()).contains("mvn clean test");
    }
}

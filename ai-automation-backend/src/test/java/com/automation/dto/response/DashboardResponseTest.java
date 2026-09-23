package com.automation.dto.response;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardResponseTest {

    private static DashboardResponse.TopWorkflowEntry sampleTopWorkflow() {
        return new DashboardResponse.TopWorkflowEntry("wf-1", "Build & Deploy", 42L);
    }

    private static DashboardResponse.TimeSeriesPoint sampleTimeSeriesPoint() {
        return new DashboardResponse.TimeSeriesPoint("2026-04-25", 100L, 0.95, 5L, 1234.5, 12.34, 50_000L);
    }

    private static DashboardResponse.ProviderStats sampleProviderStats() {
        return new DashboardResponse.ProviderStats("anthropic", 50L, 100_000L, 25.5, 0.98);
    }

    private static DashboardResponse.ModelStats sampleModelStats() {
        return new DashboardResponse.ModelStats("claude-sonnet-4-6", 200L, 80_000L, 18.75);
    }

    private static DashboardResponse.GateStatusEntry sampleGateStatusEntry() {
        return new DashboardResponse.GateStatusEntry("PASS", 30L);
    }

    private static DashboardResponse.FailureReasonEntry sampleFailureReasonEntry() {
        return new DashboardResponse.FailureReasonEntry("timeout", 7L);
    }

    private static DashboardResponse.CostByWorkflowEntry sampleCostByWorkflowEntry() {
        return new DashboardResponse.CostByWorkflowEntry("wf-2", "Daily Report", 9.99, 12L);
    }

    private static DashboardResponse.BlockTypeUsageEntry sampleBlockTypeUsageEntry() {
        return new DashboardResponse.BlockTypeUsageEntry("simple", 500L, 3L, 0.006);
    }

    private static DashboardResponse.IntegrationStats sampleIntegrationStats() {
        return new DashboardResponse.IntegrationStats("trello", 12L, 0L, 1.0);
    }

    private static DashboardResponse.TriggerDistribution sampleTriggerDistribution() {
        return new DashboardResponse.TriggerDistribution(10L, 20L, 5L, 3L, 2L, 4L);
    }

    private static DashboardResponse sample() {
        return new DashboardResponse(
                10L,         // totalWorkflows
                500L,        // totalRuns
                25L,         // runsToday
                480L,        // successfulRuns
                20L,         // failedRuns
                1500.5,      // averageDurationMs
                0.96,        // successRate
                123.45,      // totalCostUsd30d
                500_000L,    // totalTokens30d
                300_000L,    // totalInputTokens
                200_000L,    // totalOutputTokens
                List.of(sampleTopWorkflow()),
                List.of(),   // recentRuns - empty list to avoid WorkflowRunResponse construction
                List.of(sampleTimeSeriesPoint()),
                List.of(sampleProviderStats()),
                List.of(sampleIntegrationStats()),
                List.of(sampleModelStats()),
                List.of(sampleGateStatusEntry()),
                List.of(sampleFailureReasonEntry()),
                List.of(sampleCostByWorkflowEntry()),
                List.of(sampleBlockTypeUsageEntry()),
                sampleTriggerDistribution()
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_scalar_fields_when_constructed_with_full_values() {
            DashboardResponse response = sample();

            assertThat(response.totalWorkflows()).isEqualTo(10L);
            assertThat(response.totalRuns()).isEqualTo(500L);
            assertThat(response.runsToday()).isEqualTo(25L);
            assertThat(response.successfulRuns()).isEqualTo(480L);
            assertThat(response.failedRuns()).isEqualTo(20L);
            assertThat(response.averageDurationMs()).isEqualTo(1500.5);
            assertThat(response.successRate()).isEqualTo(0.96);
            assertThat(response.totalCostUsd30d()).isEqualTo(123.45);
            assertThat(response.totalTokens30d()).isEqualTo(500_000L);
            assertThat(response.totalInputTokens()).isEqualTo(300_000L);
            assertThat(response.totalOutputTokens()).isEqualTo(200_000L);
        }

        @Test
        void should_expose_all_collection_fields_when_constructed_with_full_values() {
            DashboardResponse response = sample();

            assertThat(response.topWorkflows()).hasSize(1).containsExactly(sampleTopWorkflow());
            assertThat(response.recentRuns()).isEmpty();
            assertThat(response.timeSeries()).hasSize(1).containsExactly(sampleTimeSeriesPoint());
            assertThat(response.providerStats()).hasSize(1).containsExactly(sampleProviderStats());
            assertThat(response.integrationStats()).hasSize(1).containsExactly(sampleIntegrationStats());
            assertThat(response.modelStats()).hasSize(1).containsExactly(sampleModelStats());
            assertThat(response.gateStatusSummary()).hasSize(1).containsExactly(sampleGateStatusEntry());
            assertThat(response.failureReasons()).hasSize(1).containsExactly(sampleFailureReasonEntry());
            assertThat(response.costByWorkflow()).hasSize(1).containsExactly(sampleCostByWorkflowEntry());
            assertThat(response.blockTypeUsage()).hasSize(1).containsExactly(sampleBlockTypeUsageEntry());
            assertThat(response.triggerDistribution()).isEqualTo(sampleTriggerDistribution());
        }

        @Test
        void should_allow_null_collection_and_object_fields() {
            DashboardResponse response = new DashboardResponse(
                    0L, 0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, 0L, 0L, 0L,
                    null, null, null, null, null, null, null, null, null, null, null);

            assertThat(response.topWorkflows()).isNull();
            assertThat(response.recentRuns()).isNull();
            assertThat(response.timeSeries()).isNull();
            assertThat(response.providerStats()).isNull();
            assertThat(response.integrationStats()).isNull();
            assertThat(response.modelStats()).isNull();
            assertThat(response.gateStatusSummary()).isNull();
            assertThat(response.failureReasons()).isNull();
            assertThat(response.costByWorkflow()).isNull();
            assertThat(response.blockTypeUsage()).isNull();
            assertThat(response.triggerDistribution()).isNull();
        }
    }

    @Nested
    class RecordSemantics {

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sample()).isEqualTo(sample());
        }

        @Test
        void should_have_consistent_hashCode_for_equal_records() {
            assertThat(sample().hashCode()).isEqualTo(sample().hashCode());
        }

        @Test
        void should_include_scalar_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text)
                    .contains("totalWorkflows=10")
                    .contains("totalRuns=500")
                    .contains("runsToday=25");
        }
    }

    @Nested
    class TopWorkflowEntryRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.TopWorkflowEntry entry = sampleTopWorkflow();

            assertThat(entry.workflowId()).isEqualTo("wf-1");
            assertThat(entry.workflowName()).isEqualTo("Build & Deploy");
            assertThat(entry.runCount()).isEqualTo(42L);
        }

        @Test
        void should_allow_null_string_fields() {
            DashboardResponse.TopWorkflowEntry entry = new DashboardResponse.TopWorkflowEntry(null, null, 0L);

            assertThat(entry.workflowId()).isNull();
            assertThat(entry.workflowName()).isNull();
            assertThat(entry.runCount()).isZero();
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleTopWorkflow()).isEqualTo(sampleTopWorkflow());
            assertThat(sampleTopWorkflow().hashCode()).isEqualTo(sampleTopWorkflow().hashCode());
        }
    }

    @Nested
    class TimeSeriesPointRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.TimeSeriesPoint point = sampleTimeSeriesPoint();

            assertThat(point.date()).isEqualTo("2026-04-25");
            assertThat(point.count()).isEqualTo(100L);
            assertThat(point.successRate()).isEqualTo(0.95);
            assertThat(point.failedCount()).isEqualTo(5L);
            assertThat(point.avgDurationMs()).isEqualTo(1234.5);
            assertThat(point.totalCostUsd()).isEqualTo(12.34);
            assertThat(point.totalTokens()).isEqualTo(50_000L);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleTimeSeriesPoint()).isEqualTo(sampleTimeSeriesPoint());
        }
    }

    @Nested
    class ProviderStatsRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.ProviderStats stats = sampleProviderStats();

            assertThat(stats.provider()).isEqualTo("anthropic");
            assertThat(stats.runCount()).isEqualTo(50L);
            assertThat(stats.totalTokens()).isEqualTo(100_000L);
            assertThat(stats.totalCostUsd()).isEqualTo(25.5);
            assertThat(stats.successRate()).isEqualTo(0.98);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleProviderStats()).isEqualTo(sampleProviderStats());
        }
    }

    @Nested
    class ModelStatsRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.ModelStats stats = sampleModelStats();

            assertThat(stats.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(stats.stepCount()).isEqualTo(200L);
            assertThat(stats.totalTokens()).isEqualTo(80_000L);
            assertThat(stats.totalCostUsd()).isEqualTo(18.75);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleModelStats()).isEqualTo(sampleModelStats());
        }
    }

    @Nested
    class GateStatusEntryRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.GateStatusEntry entry = sampleGateStatusEntry();

            assertThat(entry.status()).isEqualTo("PASS");
            assertThat(entry.count()).isEqualTo(30L);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleGateStatusEntry()).isEqualTo(sampleGateStatusEntry());
        }
    }

    @Nested
    class FailureReasonEntryRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.FailureReasonEntry entry = sampleFailureReasonEntry();

            assertThat(entry.reason()).isEqualTo("timeout");
            assertThat(entry.count()).isEqualTo(7L);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleFailureReasonEntry()).isEqualTo(sampleFailureReasonEntry());
        }
    }

    @Nested
    class CostByWorkflowEntryRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.CostByWorkflowEntry entry = sampleCostByWorkflowEntry();

            assertThat(entry.workflowId()).isEqualTo("wf-2");
            assertThat(entry.workflowName()).isEqualTo("Daily Report");
            assertThat(entry.totalCostUsd()).isEqualTo(9.99);
            assertThat(entry.runCount()).isEqualTo(12L);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleCostByWorkflowEntry()).isEqualTo(sampleCostByWorkflowEntry());
        }
    }

    @Nested
    class BlockTypeUsageEntryRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.BlockTypeUsageEntry entry = sampleBlockTypeUsageEntry();

            assertThat(entry.blockType()).isEqualTo("simple");
            assertThat(entry.stepCount()).isEqualTo(500L);
            assertThat(entry.failedCount()).isEqualTo(3L);
            assertThat(entry.failureRate()).isEqualTo(0.006);
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleBlockTypeUsageEntry()).isEqualTo(sampleBlockTypeUsageEntry());
        }
    }

    @Nested
    class TriggerDistributionRecord {

        @Test
        void should_expose_all_fields() {
            DashboardResponse.TriggerDistribution dist = sampleTriggerDistribution();

            assertThat(dist.manual()).isEqualTo(10L);
            assertThat(dist.scheduled()).isEqualTo(20L);
            assertThat(dist.webhook()).isEqualTo(5L);
            assertThat(dist.completionTrigger()).isEqualTo(3L);
            assertThat(dist.todoTrigger()).isEqualTo(2L);
        }

        @Test
        void should_allow_zero_values() {
            DashboardResponse.TriggerDistribution dist = new DashboardResponse.TriggerDistribution(0L, 0L, 0L, 0L, 0L, 0L);

            assertThat(dist.manual()).isZero();
            assertThat(dist.scheduled()).isZero();
            assertThat(dist.webhook()).isZero();
            assertThat(dist.completionTrigger()).isZero();
            assertThat(dist.todoTrigger()).isZero();
        }

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sampleTriggerDistribution()).isEqualTo(sampleTriggerDistribution());
            assertThat(sampleTriggerDistribution().hashCode()).isEqualTo(sampleTriggerDistribution().hashCode());
        }
    }
}

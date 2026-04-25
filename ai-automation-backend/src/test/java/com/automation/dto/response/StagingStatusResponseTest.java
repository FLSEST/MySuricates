package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StagingStatusResponseTest {

    private StagingStatusResponse sample() {
        return new StagingStatusResponse(
                true,
                true,
                "BUILD",
                2,
                5,
                "snapshot-2026-04-25",
                null,
                "0.7.1",
                "2026-04-25T10:15:30Z"
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_exposeAllFields_when_constructedWithValues() {
            StagingStatusResponse response = sample();

            assertThat(response.containerRunning()).isTrue();
            assertThat(response.healthy()).isTrue();
            assertThat(response.promotePhase()).isEqualTo("BUILD");
            assertThat(response.promoteStep()).isEqualTo(2);
            assertThat(response.promoteTotalSteps()).isEqualTo(5);
            assertThat(response.lastSnapshot()).isEqualTo("snapshot-2026-04-25");
            assertThat(response.error()).isNull();
            assertThat(response.stableVersion()).isEqualTo("0.7.1");
            assertThat(response.stableBuildTimestamp()).isEqualTo("2026-04-25T10:15:30Z");
        }

        @Test
        void should_allowNullStrings_when_constructedWithNulls() {
            StagingStatusResponse response = new StagingStatusResponse(
                    false, false, null, 0, 0, null, null, null, null);

            assertThat(response.containerRunning()).isFalse();
            assertThat(response.healthy()).isFalse();
            assertThat(response.promotePhase()).isNull();
            assertThat(response.promoteStep()).isZero();
            assertThat(response.promoteTotalSteps()).isZero();
            assertThat(response.lastSnapshot()).isNull();
            assertThat(response.error()).isNull();
            assertThat(response.stableVersion()).isNull();
            assertThat(response.stableBuildTimestamp()).isNull();
        }

        @Test
        void should_carryErrorMessage_when_errorIsSet() {
            StagingStatusResponse response = new StagingStatusResponse(
                    false, false, "ERROR", 0, 5, null,
                    "Container failed to start", null, null);

            assertThat(response.containerRunning()).isFalse();
            assertThat(response.healthy()).isFalse();
            assertThat(response.error()).isEqualTo("Container failed to start");
            assertThat(response.promotePhase()).isEqualTo("ERROR");
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_beEqual_when_fieldsMatch() {
            StagingStatusResponse a = sample();
            StagingStatusResponse b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_notBeEqual_when_oneFieldDiffers() {
            StagingStatusResponse a = sample();
            StagingStatusResponse b = new StagingStatusResponse(
                    true, true, "BUILD", 3, 5, "snapshot-2026-04-25",
                    null, "0.7.1", "2026-04-25T10:15:30Z");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_includeFieldValuesInToString() {
            StagingStatusResponse response = sample();

            assertThat(response.toString())
                    .contains("BUILD")
                    .contains("0.7.1")
                    .contains("snapshot-2026-04-25");
        }
    }
}

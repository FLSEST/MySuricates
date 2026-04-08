package com.automation.dto.response;

import com.automation.entity.ContainerStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerResponseTest {

    private static final Instant STARTED_AT = Instant.parse("2025-11-03T14:22:37.456Z");
    private static final Instant STOPPED_AT = Instant.parse("2025-11-03T15:10:00.789Z");

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc123", "myimage:latest", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(response.id()).isEqualTo("c-1");
        assertThat(response.dockerContainerId()).isEqualTo("docker-abc123");
        assertThat(response.imageName()).isEqualTo("myimage:latest");
        assertThat(response.status()).isEqualTo(ContainerStatus.RUNNING);
        assertThat(response.startedAt()).isEqualTo(STARTED_AT);
        assertThat(response.stoppedAt()).isEqualTo(STOPPED_AT);
    }

    // --- Null cases ---

    @Test
    void should_acceptNullId_when_constructed() {
        var response = new ContainerResponse(null, "docker-abc", "img:1", ContainerStatus.CREATED, STARTED_AT, STOPPED_AT);

        assertThat(response.id()).isNull();
        assertThat(response.dockerContainerId()).isEqualTo("docker-abc");
    }

    @Test
    void should_acceptNullDockerContainerId_when_constructed() {
        var response = new ContainerResponse("c-1", null, "img:1", ContainerStatus.CREATED, STARTED_AT, STOPPED_AT);

        assertThat(response.dockerContainerId()).isNull();
        assertThat(response.id()).isEqualTo("c-1");
    }

    @Test
    void should_acceptNullImageName_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", null, ContainerStatus.CREATED, STARTED_AT, STOPPED_AT);

        assertThat(response.imageName()).isNull();
    }

    @Test
    void should_acceptNullStatus_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", null, STARTED_AT, STOPPED_AT);

        assertThat(response.status()).isNull();
    }

    @Test
    void should_acceptNullStartedAt_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, null, STOPPED_AT);

        assertThat(response.startedAt()).isNull();
        assertThat(response.stoppedAt()).isEqualTo(STOPPED_AT);
    }

    @Test
    void should_acceptNullStoppedAt_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, null);

        assertThat(response.stoppedAt()).isNull();
        assertThat(response.startedAt()).isEqualTo(STARTED_AT);
    }

    @Test
    void should_acceptAllNullableFieldsNull_when_constructed() {
        var response = new ContainerResponse(null, null, null, null, null, null);

        assertThat(response.id()).isNull();
        assertThat(response.dockerContainerId()).isNull();
        assertThat(response.imageName()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.startedAt()).isNull();
        assertThat(response.stoppedAt()).isNull();
    }

    // --- ContainerStatus enum values ---

    @Test
    void should_acceptCreatedStatus_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.CREATED, null, null);

        assertThat(response.status()).isEqualTo(ContainerStatus.CREATED);
    }

    @Test
    void should_acceptRunningStatus_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, null);

        assertThat(response.status()).isEqualTo(ContainerStatus.RUNNING);
    }

    @Test
    void should_acceptStoppedStatus_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.STOPPED, STARTED_AT, STOPPED_AT);

        assertThat(response.status()).isEqualTo(ContainerStatus.STOPPED);
    }

    @Test
    void should_acceptErrorStatus_when_constructed() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.ERROR, STARTED_AT, STOPPED_AT);

        assertThat(response.status()).isEqualTo(ContainerStatus.ERROR);
    }

    @Test
    void should_coverAllEnumValues_when_iteratingContainerStatus() {
        assertThat(ContainerStatus.values()).containsExactlyInAnyOrder(
                ContainerStatus.CREATED,
                ContainerStatus.RUNNING,
                ContainerStatus.STOPPED,
                ContainerStatus.ERROR
        );
    }

    // --- Instant edge cases ---

    @Test
    void should_acceptEpochInstant_when_constructedWithStartedAt() {
        var epoch = Instant.EPOCH;
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, epoch, null);

        assertThat(response.startedAt()).isEqualTo(epoch);
        assertThat(response.startedAt().getEpochSecond()).isZero();
    }

    @Test
    void should_acceptEpochInstant_when_constructedWithStoppedAt() {
        var epoch = Instant.EPOCH;
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.STOPPED, STARTED_AT, epoch);

        assertThat(response.stoppedAt()).isEqualTo(epoch);
        assertThat(response.stoppedAt().getEpochSecond()).isZero();
    }

    @Test
    void should_preserveNanosecondPrecision_when_constructedWithStartedAt() {
        var precise = Instant.parse("2025-06-15T10:30:00.123456789Z");
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, precise, null);

        assertThat(response.startedAt()).isEqualTo(precise);
        assertThat(response.startedAt().getNano()).isEqualTo(123456789);
    }

    @Test
    void should_preserveNanosecondPrecision_when_constructedWithStoppedAt() {
        var precise = Instant.parse("2025-06-15T11:45:00.987654321Z");
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.STOPPED, STARTED_AT, precise);

        assertThat(response.stoppedAt()).isEqualTo(precise);
        assertThat(response.stoppedAt().getNano()).isEqualTo(987654321);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:latest", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:latest", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-2", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDockerContainerId() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-xyz", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentImageName() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:2", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStatus() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.STOPPED, STARTED_AT, STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStartedAt() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, Instant.parse("2026-01-01T00:00:00Z"), STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStoppedAt() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothHaveAllNullableFieldsNull() {
        var a = new ContainerResponse(null, null, null, null, null, null);
        var b = new ContainerResponse(null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nullVsNonNullStatus() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", null, STARTED_AT, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_nullVsNonNullStartedAt() {
        var a = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, null, STOPPED_AT);
        var b = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new ContainerResponse("c-1", "docker-abc", "img:1", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new ContainerResponse("c-1", "docker-abc123", "myimage:latest", ContainerStatus.RUNNING, STARTED_AT, STOPPED_AT);
        var str = response.toString();

        assertThat(str).contains("c-1");
        assertThat(str).contains("docker-abc123");
        assertThat(str).contains("myimage:latest");
        assertThat(str).contains("RUNNING");
        assertThat(str).contains("2025-11-03T14:22:37.456Z");
        assertThat(str).contains("2025-11-03T15:10:00.789Z");
    }

    @Test
    void should_handleNullsInToString_when_nullableFieldsAreNull() {
        var response = new ContainerResponse(null, null, null, null, null, null);
        var str = response.toString();

        assertThat(str).contains("null");
    }
}

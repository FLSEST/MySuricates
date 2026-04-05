package com.automation.service.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SystemIdentityServiceTest {

    private SystemIdentityService service;

    @BeforeEach
    void setUp() {
        service = new SystemIdentityService();
    }

    // --- init() with SNAPSHOT version ---

    @Test
    void should_splitVersionAndBuildType_when_snapshotVersion() {
        ReflectionTestUtils.setField(service, "rawVersion", "0.6.4-SNAPSHOT");

        service.init();

        assertThat(service.getVersion()).isEqualTo("0.6.4");
        assertThat(service.getBuildType()).isEqualTo("SNAPSHOT");
    }

    @Test
    void should_splitVersionAndBuildType_when_rcVersion() {
        ReflectionTestUtils.setField(service, "rawVersion", "1.0.0-RC1");

        service.init();

        assertThat(service.getVersion()).isEqualTo("1.0.0");
        assertThat(service.getBuildType()).isEqualTo("RC1");
    }

    @Test
    void should_splitOnFirstDash_when_multipleDashesPresent() {
        ReflectionTestUtils.setField(service, "rawVersion", "2.0.0-beta-3");

        service.init();

        assertThat(service.getVersion()).isEqualTo("2.0.0");
        assertThat(service.getBuildType()).isEqualTo("beta-3");
    }

    // --- init() with Release version ---

    @Test
    void should_setReleaseType_when_noDashInVersion() {
        ReflectionTestUtils.setField(service, "rawVersion", "1.0.0");

        service.init();

        assertThat(service.getVersion()).isEqualTo("1.0.0");
        assertThat(service.getBuildType()).isEqualTo("RELEASE");
    }

    @Test
    void should_setReleaseType_when_singleDigitVersion() {
        ReflectionTestUtils.setField(service, "rawVersion", "5");

        service.init();

        assertThat(service.getVersion()).isEqualTo("5");
        assertThat(service.getBuildType()).isEqualTo("RELEASE");
    }

    // --- getName() ---

    @Test
    void should_returnMySuricates_when_getName() {
        assertThat(service.getName()).isEqualTo("MySuricates");
    }

    // --- getVersionedName() ---

    @Test
    void should_returnNameWithVersion_when_getVersionedName() {
        ReflectionTestUtils.setField(service, "rawVersion", "0.6.4-SNAPSHOT");
        service.init();

        assertThat(service.getVersionedName()).isEqualTo("MySuricates v0.6.4");
    }

    @Test
    void should_returnNameWithReleaseVersion_when_releaseVersion() {
        ReflectionTestUtils.setField(service, "rawVersion", "1.2.3");
        service.init();

        assertThat(service.getVersionedName()).isEqualTo("MySuricates v1.2.3");
    }

    // --- getGitTrailer() ---

    @Test
    void should_returnGeneratedByTrailer_when_getGitTrailer() {
        ReflectionTestUtils.setField(service, "rawVersion", "0.6.4-SNAPSHOT");
        service.init();

        assertThat(service.getGitTrailer()).isEqualTo("Generated-By: MySuricates v0.6.4");
    }

    // --- getAgentIdentityBlock() ---

    @Test
    void should_containVersionedName_when_getAgentIdentityBlock() {
        ReflectionTestUtils.setField(service, "rawVersion", "0.6.4-SNAPSHOT");
        service.init();

        String block = service.getAgentIdentityBlock();

        assertThat(block).contains("MySuricates v0.6.4");
    }

    @Test
    void should_containGitTrailer_when_getAgentIdentityBlock() {
        ReflectionTestUtils.setField(service, "rawVersion", "0.6.4-SNAPSHOT");
        service.init();

        String block = service.getAgentIdentityBlock();

        assertThat(block).contains("Generated-By: MySuricates v0.6.4");
    }

    @Test
    void should_containSystemIdentityHeader_when_getAgentIdentityBlock() {
        ReflectionTestUtils.setField(service, "rawVersion", "1.0.0");
        service.init();

        String block = service.getAgentIdentityBlock();

        assertThat(block).contains("## System Identity");
        assertThat(block).contains("Agent von MySuricates v1.0.0");
        assertThat(block).contains("Workflow-Automations-Plattform");
        assertThat(block).contains("Git-Commits");
    }

    // --- buildTimestamp ---

    @Test
    void should_exposeBuildTimestamp_when_set() {
        ReflectionTestUtils.setField(service, "buildTimestamp", "2026-04-05T12:00:00Z");

        assertThat(service.getBuildTimestamp()).isEqualTo("2026-04-05T12:00:00Z");
    }
}

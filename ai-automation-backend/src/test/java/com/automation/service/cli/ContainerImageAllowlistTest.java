package com.automation.service.cli;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerImageAllowlistTest {

    private ContainerImageAllowlist newAllowlist() {
        return new ContainerImageAllowlist();
    }

    @Test
    void isAllowed_emptyAllowlist_allowsAnyImage() {
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of());

        assertThat(allowlist.isAllowed("anything:latest")).isTrue();
        assertThat(allowlist.isAllowed("registry.example.com/ns/img:v1")).isTrue();
    }

    @Test
    void isAllowed_exactMatch_isAllowed() {
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of("automation-agent-devops:latest"));

        assertThat(allowlist.isAllowed("automation-agent-devops:latest")).isTrue();
        assertThat(allowlist.isAllowed("automation-agent-devops:v2")).isFalse();
        assertThat(allowlist.isAllowed("other-image:latest")).isFalse();
    }

    @Test
    void isAllowed_registryPrefixEntry_matchesAnyImageUnderIt() {
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of("ghcr.io/myorg/"));

        assertThat(allowlist.isAllowed("ghcr.io/myorg/tool:latest")).isTrue();
        assertThat(allowlist.isAllowed("ghcr.io/myorg/sub/tool:latest")).isTrue();
        assertThat(allowlist.isAllowed("ghcr.io/otherorg/tool:latest")).isFalse();
    }

    @Test
    void isAllowed_tagFreeEntry_matchesBareNameAndAnyTag() {
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of("automation-agent-python"));

        assertThat(allowlist.isAllowed("automation-agent-python")).isTrue();
        assertThat(allowlist.isAllowed("automation-agent-python:latest")).isTrue();
        assertThat(allowlist.isAllowed("automation-agent-python:3.12")).isTrue();
        assertThat(allowlist.isAllowed("automation-agent-python-extra:latest")).isFalse();
    }

    @Test
    void isAllowed_tagFreeEntryWithRegistryPort_matchesBareNameAndAnyTagNotSubstringImage() {
        // Regression: a registry host may itself contain ":" (a port), so "has a tag" must be
        // decided from the last path segment only, not from entry.contains(":").
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of("localhost:5000/myimage"));

        assertThat(allowlist.isAllowed("localhost:5000/myimage")).isTrue();
        assertThat(allowlist.isAllowed("localhost:5000/myimage:v2")).isTrue();
        assertThat(allowlist.isAllowed("localhost:5000/myimage-extra:v2")).isFalse();
    }

    @Test
    void isAllowed_taggedEntryWithRegistryPort_matchesOnlyExactly() {
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of("localhost:5000/myimage:v1"));

        assertThat(allowlist.isAllowed("localhost:5000/myimage:v1")).isTrue();
        assertThat(allowlist.isAllowed("localhost:5000/myimage:v2")).isFalse();
        assertThat(allowlist.isAllowed("localhost:5000/myimage")).isFalse();
    }

    @Test
    void isAllowed_nullOrBlankImage_isRejectedWhenAllowlistConfigured() {
        ContainerImageAllowlist allowlist = newAllowlist();
        allowlist.setEntries(List.of("safe-image"));

        assertThat(allowlist.isAllowed(null)).isFalse();
        assertThat(allowlist.isAllowed("")).isFalse();
        assertThat(allowlist.isAllowed("   ")).isFalse();
    }

    @Test
    void init_blankRaw_resultsInAllowAll() {
        ContainerImageAllowlist allowlist = newAllowlist();
        ReflectionTestUtils.setField(allowlist, "allowedImagesRaw", "");

        allowlist.init();

        assertThat(allowlist.getEntries()).isEmpty();
        assertThat(allowlist.isAllowed("anything:latest")).isTrue();
    }

    @Test
    void init_commaSeparatedWithWhitespace_trimsAndSplitsEntries() {
        ContainerImageAllowlist allowlist = newAllowlist();
        ReflectionTestUtils.setField(allowlist, "allowedImagesRaw",
                " automation-agent-devops:latest ,  ghcr.io/myorg/ , ,automation-agent-python");

        allowlist.init();

        assertThat(allowlist.getEntries()).containsExactly(
                "automation-agent-devops:latest", "ghcr.io/myorg/", "automation-agent-python");
    }

    @Test
    void init_nullRaw_resultsInAllowAll() {
        ContainerImageAllowlist allowlist = newAllowlist();
        ReflectionTestUtils.setField(allowlist, "allowedImagesRaw", null);

        allowlist.init();

        assertThat(allowlist.getEntries()).isEmpty();
    }
}

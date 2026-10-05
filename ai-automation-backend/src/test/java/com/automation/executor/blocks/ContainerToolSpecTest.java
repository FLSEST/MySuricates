package com.automation.executor.blocks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerToolSpecTest {

    @Test
    void defaults() {
        ContainerToolSpec spec = ContainerToolSpec.from(Map.of("container_image", " img:1 ",
                "container_args", List.of("fs", 7)), "Trivy Scan");

        assertThat(spec.image()).isEqualTo("img:1");
        assertThat(spec.args()).containsExactly("fs", "7");
        assertThat(spec.sarif()).isFalse();
        assertThat(spec.outputDir()).isEqualTo("/out");
        assertThat(spec.cacheDir()).isNull();
        assertThat(spec.factPrefix()).isEqualTo("trivy_scan");
        assertThat(spec.mounts()).isEmpty();
        assertThat(spec.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void explicitValues() {
        ContainerToolSpec spec = ContainerToolSpec.from(Map.of("container_image", "img", "container_args", List.of(),
                "output_format", "sarif", "output_dir", "/report", "cache_dir", "/cache", "fact_prefix", "deps",
                "timeout_seconds", 900, "mount_docker_socket", true), "trivy");

        assertThat(spec.sarif()).isTrue();
        assertThat(spec.outputDir()).isEqualTo("/report");
        assertThat(spec.cacheDir()).isEqualTo("/cache");
        assertThat(spec.factPrefix()).isEqualTo("deps");
        assertThat(spec.timeoutSeconds()).isEqualTo(900);
        assertThat(spec.mountDockerSocket()).isTrue();
    }

    @Test
    void argsThatAreNoList_areNull() {
        assertThat(ContainerToolSpec.from(Map.of("container_args", "fs /workspace"), "x").args()).isNull();
    }

    @Test
    void factPrefixIsSanitized() {
        assertThat(ContainerToolSpec.factPrefix("Prüfung 2")).isEqualTo("pr_fung_2");
        assertThat(ContainerToolSpec.factPrefix("__x__")).isEqualTo("x");
        assertThat(ContainerToolSpec.factPrefix(null)).isEqualTo("tool");
        assertThat(ContainerToolSpec.factPrefix("äö")).isEqualTo("tool");
    }
}

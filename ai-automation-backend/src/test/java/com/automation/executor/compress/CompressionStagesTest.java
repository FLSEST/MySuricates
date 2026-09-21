package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressionStagesTest {

    @Test
    void ops_areListedInDocumentationOrder() {
        assertThat(CompressionStages.ops()).containsExactly(
                "text.normalize", "text.truncate",
                "lines.filter", "lines.replace", "lines.dedupe", "lines.head", "lines.tail",
                "markdown.section",
                "json.select", "json.limit", "json.sort", "json.to_lines");
    }

    @Test
    void ops_isAnImmutableCopy() {
        assertThatThrownBy(() -> CompressionStages.ops().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(CompressionStages.ops()).doesNotContain("x");
    }

    @Test
    void find_returnsRegisteredStageByOp_orEmpty() {
        assertThat(CompressionStages.find("lines.head")).get().extracting(CompressionStage::op).isEqualTo("lines.head");
        assertThat(CompressionStages.find("lines.tail")).get().extracting(CompressionStage::op).isEqualTo("lines.tail");
        assertThat(CompressionStages.find("lines.head").get()).isSameAs(CompressionStages.find("lines.head").get());
        assertThat(CompressionStages.find(null)).isEmpty();
        assertThat(CompressionStages.find("nope")).isEmpty();
        assertThat(CompressionStages.find("LINES.HEAD")).as("case-sensitive").isEmpty();
    }
}

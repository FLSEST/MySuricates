package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.executor.spec.BlockSpecs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WishSystemPromptTest {

    @Test
    void blockTypesSindAlleImSchemaBekannt() {
        assertThat(BlockSpecs.types()).containsAll(WishSystemPrompt.BLOCK_TYPES);
        assertThat(WishSystemPrompt.BLOCK_TYPES).doesNotHaveDuplicates()
                .doesNotContain("forEach"); // der Typ heisst foreach (TODO cf8db6f8)
    }

    @Test
    void textEnthaeltDieBlockReferenzDerErlaubtenTypen() {
        String text = WishSystemPrompt.text(new WishConfig());

        assertThat(text).contains(BlockSpecs.reference(WishSystemPrompt.BLOCK_TYPES));
    }

    @Test
    void timeoutRichtlinienNennenDieWerteAusDerConfig() {
        WishConfig config = new WishConfig();
        config.setDefaultShellTimeoutSeconds(111);
        config.setDefaultAgenticTimeoutSeconds(222);
        config.setDefaultAgenticIterationTimeoutSeconds(333);
        config.setDefaultAgenticTotalTimeoutSeconds(444);

        String text = WishSystemPrompt.text(config);

        assertThat(text).contains("`timeout_seconds` 111")
                .contains("`timeout_seconds` 222 per CLI call")
                .contains("`iteration_timeout_seconds` 333")
                .contains("`total_timeout_seconds` 444");
    }

    @Test
    void keinFormatPlatzhalterBleibtUnaufgeloest() {
        String text = WishSystemPrompt.text(new WishConfig());

        assertThat(text).doesNotContain("%d").doesNotContain("%s");
    }

    @Test
    void ausgabeFormatUndPflichtSchluesselSindEnthalten() {
        String text = WishSystemPrompt.text(new WishConfig());

        assertThat(text).contains("## Available Block Types", "## Design Principles", "## Timeout Guidelines",
                "## Output Format", "\"needsClarification\"", "\"clarificationQuestion\"", "\"workflows\"",
                "{{ previous_output }}", "Ausfuehrungsumgebung");
    }
}

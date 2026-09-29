package com.automation.service.dialog;

import com.automation.service.assistant.fetch.BlockSpecs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialogConversionPromptTest {

    @Test
    void blockTypesSindImSchemaBekanntUndOhneDuplikate() {
        assertThat(BlockSpecs.types()).containsAll(DialogConversionPrompt.BLOCK_TYPES);
        assertThat(DialogConversionPrompt.BLOCK_TYPES).doesNotHaveDuplicates().doesNotContain("forEach");
    }

    @Test
    void buildSetztProjektTurnsUndTranskriptEin() {
        String prompt = DialogConversionPrompt.build("/pfad/zum/projekt", 12, "USER: mach x\nASSISTANT: ok");

        assertThat(prompt).contains("- Projekt: /pfad/zum/projekt", "- Turns: 12", "USER: mach x\nASSISTANT: ok");
    }

    @Test
    void buildEnthaeltDieBlockReferenzUndDasAntwortSchema() {
        String prompt = DialogConversionPrompt.build("p", 1, "t");

        assertThat(prompt).endsWith(BlockSpecs.reference(DialogConversionPrompt.BLOCK_TYPES))
                .contains("\"suggestedName\"", "\"confidence\"", "\"patternType\"", "\"blocks\"");
    }

    @Test
    void transkriptMitProzentzeichenUndPlatzhalternBleibtUnveraendert() {
        String transcript = "100% fertig, {{ variable }} und %s";

        assertThat(DialogConversionPrompt.build("p", 1, transcript)).contains(transcript);
    }
}

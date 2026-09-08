package com.automation.service.assistant;

import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantResponseParserTest {

    private final AssistantResponseParser parser = new AssistantResponseParser(new ObjectMapper());

    @Test
    void parse_nullOrBlank_returnsFallbackMessage() {
        assertThat(parser.parse(null).message()).isEqualTo("(No response received)");
        assertThat(parser.parse("   ").message()).isEqualTo("(No response received)");
        assertThat(parser.parse(null).actions()).isEmpty();
    }

    @Test
    void parse_plainText_trimsAndHasNoActions() {
        var r = parser.parse("  Hallo Welt \n");

        assertThat(r.message()).isEqualTo("Hallo Welt");
        assertThat(r.actions()).isEmpty();
    }

    @Test
    void parse_actionsBlock_splitsMessageAndActions() {
        var r = parser.parse("Ich starte ihn.\n<actions>[{\"type\":\"run_workflow\",\"id\":\"wf-1\",\"name\":\"Daily\",\"label\":\"Start\"}]</actions>");

        assertThat(r.message()).isEqualTo("Ich starte ihn.");
        assertThat(r.actions()).extracting(AssistantAction::type, AssistantAction::id)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("run_workflow", "wf-1"));
    }

    @Test
    void parse_lastActionsBlockWins() {
        var r = parser.parse("<actions>[]</actions> text <actions>[{\"type\":\"show_run\",\"id\":\"r\",\"name\":\"n\",\"label\":\"l\"}]</actions>");

        assertThat(r.actions()).hasSize(1);
        assertThat(r.message()).isEqualTo("<actions>[]</actions> text");
    }

    @Test
    void parse_malformedActionsJson_keepsMessageDropsActions() {
        var r = parser.parse("Text<actions>[{not json</actions>");

        assertThat(r.message()).isEqualTo("Text");
        assertThat(r.actions()).isEmpty();
    }

    @Test
    void parse_closingTagBeforeOpening_treatedAsPlainText() {
        String raw = "</actions> oops <actions>[]";

        assertThat(parser.parse(raw).message()).isEqualTo(raw);
        assertThat(parser.parse(raw).actions()).isEmpty();
    }
}

package com.automation.service.assistant;

import com.automation.service.assistant.AssistantFetchService.FetchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantFetchPolicyTest {

    @Test
    void isAutoFetchAllowed_knownType_true() {
        assertThat(AssistantFetchPolicy.isAutoFetchAllowed(new FetchRequest("run_detail", "r1"))).isTrue();
    }

    @Test
    void isAutoFetchAllowed_unknownTypeOrNull_false() {
        assertThat(AssistantFetchPolicy.isAutoFetchAllowed(new FetchRequest("secret_dump", "x"))).isFalse();
        assertThat(AssistantFetchPolicy.isAutoFetchAllowed(null)).isFalse();
    }

    @Test
    void fetchKey_joinsTypeAndId_nullsAsEmpty() {
        assertThat(AssistantFetchPolicy.fetchKey(new FetchRequest("docs", "guide"))).isEqualTo("docs|guide");
        assertThat(AssistantFetchPolicy.fetchKey(new FetchRequest(null, null))).isEqualTo("|");
    }

    @Test
    void hasSubstantiveAnswer_blankText_false() {
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer("<fetch type=\"docs\"/>", "   ")).isFalse();
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer(null, null)).isFalse();
    }

    @Test
    void hasSubstantiveAnswer_actionsBlock_trueEvenIfShort() {
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer("Ok <actions>[]</actions>", "Ok")).isTrue();
    }

    @Test
    void hasSubstantiveAnswer_longText_true() {
        String longText = "x".repeat(AssistantFetchPolicy.ANSWER_TEXT_MIN_CHARS);
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer(longText, longText)).isTrue();
    }

    @Test
    void hasSubstantiveAnswer_shortAnnouncement_false() {
        String stall = "Ich schaue mir den Run kurz an und melde mich gleich.";
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer(stall, stall)).isFalse();
    }

    @Test
    void hasSubstantiveAnswer_shortFactualSentenceOver40Chars_true() {
        String answer = "Der Run ist gestern um 14:00 fehlgeschlagen.";
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer(answer, answer)).isTrue();
    }

    @Test
    void hasSubstantiveAnswer_veryShortNonAnnouncement_false() {
        assertThat(AssistantFetchPolicy.hasSubstantiveAnswer("Ja.", "Ja.")).isFalse();
    }
}

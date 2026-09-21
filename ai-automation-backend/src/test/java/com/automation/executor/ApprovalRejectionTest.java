package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRejectionTest {

    @Test
    void withoutConfig_noAndNeinAreRejections_caseAndPaddingIgnored() {
        List<String> rejects = ApprovalRejection.rejectAnswers(null);

        assertThat(ApprovalRejection.isRejection("No", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection(" nein ", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("NEIN", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("Ja", rejects)).isFalse();
        assertThat(ApprovalRejection.isRejection("Yes always", rejects)).isFalse();
        assertThat(ApprovalRejection.isRejection(null, rejects)).isFalse();
    }

    @Test
    void configuredRejectOptions_replaceTheDefault() {
        List<String> rejects = ApprovalRejection.rejectAnswers(List.of("Verwerfen", "Stopp"));

        assertThat(ApprovalRejection.isRejection("stopp", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("Verwerfen", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("No", rejects)).isFalse();
    }

    @Test
    void unusableConfig_fallsBackToDefault() {
        assertThat(ApprovalRejection.rejectAnswers("No")).isEqualTo(ApprovalRejection.DEFAULT_REJECT_ANSWERS);
        assertThat(ApprovalRejection.rejectAnswers(List.of())).isEqualTo(ApprovalRejection.DEFAULT_REJECT_ANSWERS);
        assertThat(ApprovalRejection.rejectAnswers(List.of(" ", 3))).isEqualTo(ApprovalRejection.DEFAULT_REJECT_ANSWERS);
    }

    @Test
    void rejectingOptions_keepsOptionSpelling() {
        List<String> rejects = ApprovalRejection.rejectAnswers(null);

        assertThat(ApprovalRejection.rejectingOptions(List.of("Ja", "Nein"), rejects)).containsExactly("Nein");
        assertThat(ApprovalRejection.rejectingOptions(List.of("Yes", "Yes always", "No"), rejects)).containsExactly("No");
        assertThat(ApprovalRejection.rejectingOptions(List.of("Weiter", "Abbrechen"), rejects)).isEmpty();
        assertThat(ApprovalRejection.rejectingOptions(null, rejects)).isEmpty();
    }
}

package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRequestTest {

    // --- builder happy-path tests ---

    @Test
    void builder_allFieldsSet_returnsRequestWithValues() {
        List<String> options = List.of("Yes", "No");

        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("block-1")
                .question("Proceed?")
                .options(options)
                .stepIndex(3)
                .type("human-input")
                .inputType("text")
                .defaultValue("default")
                .variableName("answer")
                .required(true)
                .validationPattern("^[a-z]+$")
                .build();

        assertThat(request.getBlockId()).isEqualTo("block-1");
        assertThat(request.getQuestion()).isEqualTo("Proceed?");
        assertThat(request.getOptions()).containsExactly("Yes", "No");
        assertThat(request.getStepIndex()).isEqualTo(3);
        assertThat(request.getType()).isEqualTo("human-input");
        assertThat(request.getInputType()).isEqualTo("text");
        assertThat(request.getDefaultValue()).isEqualTo("default");
        assertThat(request.getVariableName()).isEqualTo("answer");
        assertThat(request.isRequired()).isTrue();
        assertThat(request.getValidationPattern()).isEqualTo("^[a-z]+$");
    }

    // --- type default tests ---

    @Test
    void builder_typeNotSet_defaultsToApproval() {
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("block-1")
                .question("Proceed?")
                .build();

        assertThat(request.getType()).isEqualTo("approval");
    }

    @Test
    void builder_typeExplicitlySet_overridesDefault() {
        ApprovalRequest request = ApprovalRequest.builder()
                .type("human-input")
                .build();

        assertThat(request.getType()).isEqualTo("human-input");
    }

    // --- minimal builder tests ---

    @Test
    void builder_noFieldsSet_returnsRequestWithNullsAndDefaults() {
        ApprovalRequest request = ApprovalRequest.builder().build();

        assertThat(request.getBlockId()).isNull();
        assertThat(request.getQuestion()).isNull();
        assertThat(request.getOptions()).isNull();
        assertThat(request.getStepIndex()).isZero();
        assertThat(request.getType()).isEqualTo("approval");
        assertThat(request.getInputType()).isNull();
        assertThat(request.getDefaultValue()).isNull();
        assertThat(request.getVariableName()).isNull();
        assertThat(request.isRequired()).isFalse();
        assertThat(request.getValidationPattern()).isNull();
    }

    // --- options list tests ---

    @Test
    void builder_optionsEmptyList_returnsEmptyOptions() {
        ApprovalRequest request = ApprovalRequest.builder()
                .options(Collections.emptyList())
                .build();

        assertThat(request.getOptions()).isNotNull();
        assertThat(request.getOptions()).isEmpty();
    }

    @Test
    void builder_optionsSingleEntry_returnsOneOption() {
        ApprovalRequest request = ApprovalRequest.builder()
                .options(List.of("OnlyOption"))
                .build();

        assertThat(request.getOptions()).containsExactly("OnlyOption");
    }

    @Test
    void builder_optionsMultipleEntries_preservesOrder() {
        ApprovalRequest request = ApprovalRequest.builder()
                .options(List.of("Approve", "Reject", "Defer"))
                .build();

        assertThat(request.getOptions()).containsExactly("Approve", "Reject", "Defer");
    }

    // --- required flag tests ---

    @Test
    void builder_requiredTrue_returnsTrue() {
        ApprovalRequest request = ApprovalRequest.builder()
                .required(true)
                .build();

        assertThat(request.isRequired()).isTrue();
    }

    @Test
    void builder_requiredFalse_returnsFalse() {
        ApprovalRequest request = ApprovalRequest.builder()
                .required(false)
                .build();

        assertThat(request.isRequired()).isFalse();
    }

    // --- stepIndex tests ---

    @Test
    void builder_stepIndexNegative_returnsNegativeValue() {
        ApprovalRequest request = ApprovalRequest.builder()
                .stepIndex(-1)
                .build();

        assertThat(request.getStepIndex()).isEqualTo(-1);
    }

    @Test
    void builder_stepIndexLarge_returnsValue() {
        ApprovalRequest request = ApprovalRequest.builder()
                .stepIndex(Integer.MAX_VALUE)
                .build();

        assertThat(request.getStepIndex()).isEqualTo(Integer.MAX_VALUE);
    }

    // --- human-input variant tests ---

    @Test
    void builder_humanInputFields_returnsHumanInputRequest() {
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("input-1")
                .question("Enter your name")
                .type("human-input")
                .inputType("text")
                .variableName("user_name")
                .required(true)
                .validationPattern("^[A-Za-z ]+$")
                .build();

        assertThat(request.getType()).isEqualTo("human-input");
        assertThat(request.getInputType()).isEqualTo("text");
        assertThat(request.getVariableName()).isEqualTo("user_name");
        assertThat(request.isRequired()).isTrue();
        assertThat(request.getValidationPattern()).isEqualTo("^[A-Za-z ]+$");
    }

    @Test
    void builder_emptyStrings_preservesEmptyStrings() {
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("")
                .question("")
                .defaultValue("")
                .build();

        assertThat(request.getBlockId()).isEmpty();
        assertThat(request.getQuestion()).isEmpty();
        assertThat(request.getDefaultValue()).isEmpty();
    }
}

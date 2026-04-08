package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateChainRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static ChainEntryRequest sampleEntry(String workflowId) {
        return new ChainEntryRequest(workflowId, "label-" + workflowId, 1, false, Map.of());
    }

    // --- Accessor tests ---

    @Test
    void should_returnName_when_constructedWithName() {
        var request = new CreateChainRequest("My Chain", null, null, List.of(sampleEntry("w1")), null);

        assertThat(request.name()).isEqualTo("My Chain");
    }

    @Test
    void should_returnDescription_when_constructedWithDescription() {
        var request = new CreateChainRequest("Chain", "A useful chain", null, List.of(sampleEntry("w1")), null);

        assertThat(request.description()).isEqualTo("A useful chain");
    }

    @Test
    void should_returnIcon_when_constructedWithIcon() {
        var request = new CreateChainRequest("Chain", null, "rocket", List.of(sampleEntry("w1")), null);

        assertThat(request.icon()).isEqualTo("rocket");
    }

    @Test
    void should_returnEntries_when_constructedWithPopulatedList() {
        var entries = List.of(sampleEntry("w1"), sampleEntry("w2"));
        var request = new CreateChainRequest("Chain", null, null, entries, null);

        assertThat(request.entries()).hasSize(2);
        assertThat(request.entries().get(0).workflowId()).isEqualTo("w1");
        assertThat(request.entries().get(1).workflowId()).isEqualTo("w2");
    }

    @Test
    void should_returnStopOnFailure_when_constructedWithTrue() {
        var request = new CreateChainRequest("Chain", null, null, List.of(sampleEntry("w1")), true);

        assertThat(request.stopOnFailure()).isTrue();
    }

    @Test
    void should_returnStopOnFailure_when_constructedWithFalse() {
        var request = new CreateChainRequest("Chain", null, null, List.of(sampleEntry("w1")), false);

        assertThat(request.stopOnFailure()).isFalse();
    }

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var entries = List.of(sampleEntry("w1"));
        var request = new CreateChainRequest("Chain", "Desc", "star", entries, true);

        assertThat(request.name()).isEqualTo("Chain");
        assertThat(request.description()).isEqualTo("Desc");
        assertThat(request.icon()).isEqualTo("star");
        assertThat(request.entries()).containsExactlyElementsOf(entries);
        assertThat(request.stopOnFailure()).isTrue();
    }

    // --- Bean Validation: @NotBlank name ---

    @Test
    void should_passValidation_when_nameIsNotBlank() {
        var request = new CreateChainRequest("Valid Name", null, null, List.of(sampleEntry("w1")), null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new CreateChainRequest(null, null, null, List.of(sampleEntry("w1")), null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new CreateChainRequest("   ", null, null, List.of(sampleEntry("w1")), null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsEmpty() {
        var request = new CreateChainRequest("", null, null, List.of(sampleEntry("w1")), null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    // --- Bean Validation: @NotEmpty entries ---

    @Test
    void should_failValidation_when_entriesIsNull() {
        var request = new CreateChainRequest("Chain", null, null, null, null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("entries"));
    }

    @Test
    void should_failValidation_when_entriesIsEmpty() {
        var request = new CreateChainRequest("Chain", null, null, Collections.emptyList(), null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("entries");
    }

    @Test
    void should_failValidation_when_bothNameAndEntriesInvalid() {
        var request = new CreateChainRequest(null, null, null, null, null);

        Set<ConstraintViolation<CreateChainRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
        Set<String> paths = Set.of(
                violations.stream().map(v -> v.getPropertyPath().toString()).toList().get(0),
                violations.stream().map(v -> v.getPropertyPath().toString()).toList().get(1)
        );
        assertThat(paths).containsExactlyInAnyOrder("name", "entries");
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var entries = List.of(sampleEntry("w1"));
        var a = new CreateChainRequest("Chain", "Desc", "star", entries, true);
        var b = new CreateChainRequest("Chain", "Desc", "star", entries, true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyEntries() {
        var a = new CreateChainRequest("Chain", null, null, List.of(), null);
        var b = new CreateChainRequest("Chain", null, null, Collections.emptyList(), null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var entries = List.of(sampleEntry("w1"));
        var a = new CreateChainRequest("Chain A", "Desc", "star", entries, true);
        var b = new CreateChainRequest("Chain B", "Desc", "star", entries, true);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_entriesDiffer() {
        var a = new CreateChainRequest("Chain", null, null, List.of(sampleEntry("w1")), null);
        var b = new CreateChainRequest("Chain", null, null, List.of(sampleEntry("w2")), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_stopOnFailureDiffers() {
        var entries = List.of(sampleEntry("w1"));
        var a = new CreateChainRequest("Chain", null, null, entries, true);
        var b = new CreateChainRequest("Chain", null, null, entries, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new CreateChainRequest("Chain", "Desc", "star", List.of(sampleEntry("w1")), true);

        assertThat(request).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNamesInToString() {
        var request = new CreateChainRequest("Chain", "Desc", "star", List.of(), true);

        assertThat(request.toString()).contains("name");
        assertThat(request.toString()).contains("description");
        assertThat(request.toString()).contains("icon");
        assertThat(request.toString()).contains("entries");
        assertThat(request.toString()).contains("stopOnFailure");
    }

    @Test
    void should_containValuesInToString() {
        var request = new CreateChainRequest("Chain", "Desc", "star", List.of(), true);

        assertThat(request.toString()).contains("Chain");
        assertThat(request.toString()).contains("Desc");
        assertThat(request.toString()).contains("star");
    }
}

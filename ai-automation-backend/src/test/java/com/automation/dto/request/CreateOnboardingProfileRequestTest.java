package com.automation.dto.request;

import com.automation.entity.OnboardingProfileType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOnboardingProfileRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Accessor tests ---

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var name = Map.of("de", "Profil A", "en", "Profile A");
        var description = Map.of("de", "Beschreibung", "en", "Description");
        var request = new CreateOnboardingProfileRequest(
                "my-profile", name, description, "some content", OnboardingProfileType.PROFILE
        );

        assertThat(request.key()).isEqualTo("my-profile");
        assertThat(request.name()).isEqualTo(name);
        assertThat(request.description()).isEqualTo(description);
        assertThat(request.content()).isEqualTo("some content");
        assertThat(request.type()).isEqualTo(OnboardingProfileType.PROFILE);
    }

    @Test
    void should_returnBaseType_when_constructedWithBase() {
        var request = new CreateOnboardingProfileRequest(
                "base-profile", Map.of("de", "Basis"), null, "content", OnboardingProfileType.BASE
        );

        assertThat(request.type()).isEqualTo(OnboardingProfileType.BASE);
    }

    // --- Null cases for optional fields ---

    @Test
    void should_allowNullDescription_when_notProvided() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", Map.of("de", "Name"), null, "content", OnboardingProfileType.PROFILE
        );

        assertThat(request.description()).isNull();
    }

    // --- Validation: valid request ---

    @Test
    void should_passValidation_when_allRequiredFieldsAreValid() {
        var request = new CreateOnboardingProfileRequest(
                "valid-key", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // --- Validation: @NotBlank @Pattern key ---

    @Test
    void should_failValidation_when_keyIsNull() {
        var request = new CreateOnboardingProfileRequest(
                null, Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("key");
    }

    @Test
    void should_failValidation_when_keyIsEmpty() {
        var request = new CreateOnboardingProfileRequest(
                "", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("key");
    }

    @Test
    void should_failValidation_when_keyIsBlank() {
        var request = new CreateOnboardingProfileRequest(
                "   ", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("key");
    }

    @Test
    void should_failValidation_when_keyContainsUppercase() {
        var request = new CreateOnboardingProfileRequest(
                "My-Profile", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("key");
    }

    @Test
    void should_failValidation_when_keyContainsSpaces() {
        var request = new CreateOnboardingProfileRequest(
                "my profile", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("key");
    }

    @Test
    void should_failValidation_when_keyContainsSpecialChars() {
        var request = new CreateOnboardingProfileRequest(
                "my_profile!", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("key");
    }

    @Test
    void should_passValidation_when_keyContainsOnlyLowercaseNumbersAndHyphens() {
        var request = new CreateOnboardingProfileRequest(
                "my-profile-123", Map.of("de", "Name"), null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // --- Validation: @NotNull name ---

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", null, null, "content", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    // --- Validation: @NotBlank content ---

    @Test
    void should_failValidation_when_contentIsNull() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", Map.of("de", "Name"), null, null, OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("content");
    }

    @Test
    void should_failValidation_when_contentIsEmpty() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", Map.of("de", "Name"), null, "", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("content");
    }

    @Test
    void should_failValidation_when_contentIsBlank() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", Map.of("de", "Name"), null, "   ", OnboardingProfileType.BASE
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("content");
    }

    // --- Validation: @NotNull type ---

    @Test
    void should_failValidation_when_typeIsNull() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", Map.of("de", "Name"), null, "content", null
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("type");
    }

    // --- Validation: multiple invalid fields ---

    @Test
    void should_failValidation_when_allRequiredFieldsAreInvalid() {
        var request = new CreateOnboardingProfileRequest(
                null, null, null, null, null
        );

        Set<ConstraintViolation<CreateOnboardingProfileRequest>> violations = validator.validate(request);

        assertThat(violations).hasSizeGreaterThanOrEqualTo(4);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("key", "name", "content", "type");
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var name = Map.of("de", "Name");
        var desc = Map.of("de", "Beschreibung");
        var a = new CreateOnboardingProfileRequest("key-1", name, desc, "content", OnboardingProfileType.PROFILE);
        var b = new CreateOnboardingProfileRequest("key-1", name, desc, "content", OnboardingProfileType.PROFILE);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_keyDiffers() {
        var name = Map.of("de", "Name");
        var a = new CreateOnboardingProfileRequest("key-a", name, null, "content", OnboardingProfileType.BASE);
        var b = new CreateOnboardingProfileRequest("key-b", name, null, "content", OnboardingProfileType.BASE);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_typeDiffers() {
        var name = Map.of("de", "Name");
        var a = new CreateOnboardingProfileRequest("key-1", name, null, "content", OnboardingProfileType.BASE);
        var b = new CreateOnboardingProfileRequest("key-1", name, null, "content", OnboardingProfileType.PROFILE);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new CreateOnboardingProfileRequest(
                "my-key", Map.of("de", "Mein Profil"), Map.of("de", "Beschreibung"),
                "some content", OnboardingProfileType.PROFILE
        );

        String str = request.toString();
        assertThat(str).contains("my-key");
        assertThat(str).contains("Mein Profil");
        assertThat(str).contains("some content");
        assertThat(str).contains("PROFILE");
    }
}

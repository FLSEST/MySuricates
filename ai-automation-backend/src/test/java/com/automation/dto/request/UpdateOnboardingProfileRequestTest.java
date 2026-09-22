package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateOnboardingProfileRequestTest {

    @Test
    void should_returnName_when_constructedWithPopulatedMap() {
        var request = new UpdateOnboardingProfileRequest(
                Map.of("en", "Profile", "de", "Profil"), Map.of(), Map.of("de", "some content"));

        assertThat(request.name()).containsEntry("en", "Profile").containsEntry("de", "Profil");
    }

    @Test
    void should_returnDescription_when_constructedWithPopulatedMap() {
        var request = new UpdateOnboardingProfileRequest(
                Map.of(), Map.of("en", "A description", "de", "Eine Beschreibung"), Map.of("de", "some content"));

        assertThat(request.description())
                .containsEntry("en", "A description")
                .containsEntry("de", "Eine Beschreibung");
    }

    @Test
    void should_returnContent_when_constructedWithValue() {
        var request = new UpdateOnboardingProfileRequest(Map.of(), Map.of(), Map.of("de", "my content"));

        assertThat(request.content()).isEqualTo(Map.of("de", "my content"));
    }

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var name = Map.of("en", "Name");
        var description = Map.of("en", "Desc");
        var request = new UpdateOnboardingProfileRequest(name, description, Map.of("de", "content"));

        assertThat(request.name()).isEqualTo(name);
        assertThat(request.description()).isEqualTo(description);
        assertThat(request.content()).isEqualTo(Map.of("de", "content"));
    }

    @Test
    void should_returnNull_when_nameIsNull() {
        var request = new UpdateOnboardingProfileRequest(null, Map.of(), Map.of("de", "content"));

        assertThat(request.name()).isNull();
    }

    @Test
    void should_returnNull_when_descriptionIsNull() {
        var request = new UpdateOnboardingProfileRequest(Map.of(), null, Map.of("de", "content"));

        assertThat(request.description()).isNull();
    }

    @Test
    void should_returnNull_when_contentIsNull() {
        var request = new UpdateOnboardingProfileRequest(Map.of(), Map.of(), null);

        assertThat(request.content()).isNull();
    }

    @Test
    void should_returnNull_when_allFieldsAreNull() {
        var request = new UpdateOnboardingProfileRequest(null, null, null);

        assertThat(request.name()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.content()).isNull();
    }

    @Test
    void should_allowMixedNullAndPopulatedFields() {
        var request = new UpdateOnboardingProfileRequest(Map.of("en", "Name"), null, Map.of("de", "content"));

        assertThat(request.name()).containsEntry("en", "Name");
        assertThat(request.description()).isNull();
        assertThat(request.content()).isEqualTo(Map.of("de", "content"));
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D"), Map.of("de", "c"));
        var b = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D"), Map.of("de", "c"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyMapsAndNullContent() {
        var a = new UpdateOnboardingProfileRequest(Map.of(), Collections.emptyMap(), null);
        var b = new UpdateOnboardingProfileRequest(Collections.emptyMap(), Map.of(), null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new UpdateOnboardingProfileRequest(Map.of("en", "A"), Map.of("en", "D"), Map.of("de", "c"));
        var b = new UpdateOnboardingProfileRequest(Map.of("en", "B"), Map.of("en", "D"), Map.of("de", "c"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_descriptionDiffers() {
        var a = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D1"), Map.of("de", "c"));
        var b = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D2"), Map.of("de", "c"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_contentDiffers() {
        var a = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D"), Map.of("de", "c1"));
        var b = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D"), Map.of("de", "c2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D"), Map.of("de", "c"));

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldNamesInToString() {
        var request = new UpdateOnboardingProfileRequest(Map.of("en", "N"), Map.of("en", "D"), Map.of("de", "c"));

        assertThat(request.toString()).contains("name");
        assertThat(request.toString()).contains("description");
        assertThat(request.toString()).contains("content");
    }

    @Test
    void should_containValuesInToString() {
        var request = new UpdateOnboardingProfileRequest(Map.of("en", "MyName"), Map.of("en", "MyDesc"), Map.of("de", "MyContent"));

        assertThat(request.toString()).contains("MyName");
        assertThat(request.toString()).contains("MyDesc");
        assertThat(request.toString()).contains("MyContent");
    }
}

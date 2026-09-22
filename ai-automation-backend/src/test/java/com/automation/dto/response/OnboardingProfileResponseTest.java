package com.automation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.automation.entity.OnboardingProfileType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OnboardingProfileResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_expose_all_fields_when_constructed_with_full_values() {
        Instant createdAt = Instant.parse("2026-01-10T12:00:00Z");
        Instant updatedAt = Instant.parse("2026-03-15T08:30:00Z");
        Map<String, String> name = Map.of("de", "Profil A", "en", "Profile A");
        Map<String, String> description = Map.of("de", "Beschreibung", "en", "Description");

        OnboardingProfileResponse response = new OnboardingProfileResponse(
            "profile-123",
            "profile-a",
            name,
            description,
            Map.of("de", "Some content here"),
            OnboardingProfileType.PROFILE,
            true,
            5,
            2,
            3,
            createdAt,
            updatedAt
        );

        assertThat(response.id()).isEqualTo("profile-123");
        assertThat(response.key()).isEqualTo("profile-a");
        assertThat(response.name()).isEqualTo(name);
        assertThat(response.description()).isEqualTo(description);
        assertThat(response.content()).isEqualTo(Map.of("de", "Some content here"));
        assertThat(response.type()).isEqualTo(OnboardingProfileType.PROFILE);
        assertThat(response.builtIn()).isTrue();
        assertThat(response.sortOrder()).isEqualTo(5);
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.historyCount()).isEqualTo(3);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void should_allow_null_optional_fields_when_minimal_record() {
        OnboardingProfileResponse response = new OnboardingProfileResponse(
            "profile-min",
            "min-key",
            null,
            null,
            null,
            OnboardingProfileType.BASE,
            false,
            0,
            1,
            0,
            null,
            null
        );

        assertThat(response.id()).isEqualTo("profile-min");
        assertThat(response.key()).isEqualTo("min-key");
        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.content()).isNull();
        assertThat(response.type()).isEqualTo(OnboardingProfileType.BASE);
        assertThat(response.builtIn()).isFalse();
        assertThat(response.sortOrder()).isZero();
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.historyCount()).isZero();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_handle_empty_maps_when_name_and_description_are_empty() {
        OnboardingProfileResponse response = new OnboardingProfileResponse(
            "profile-empty",
            "empty-key",
            Map.of(),
            Map.of(),
            Map.of("de", "content"),
            OnboardingProfileType.BASE,
            false,
            1,
            1,
            0,
            Instant.now(),
            Instant.now()
        );

        assertThat(response.name()).isNotNull().isEmpty();
        assertThat(response.description()).isNotNull().isEmpty();
    }

    @Test
    void should_store_base_type_when_type_is_base() {
        OnboardingProfileResponse response = new OnboardingProfileResponse(
            "base-profile",
            "base",
            null,
            null,
            Map.of("de", "base content"),
            OnboardingProfileType.BASE,
            true,
            0,
            1,
            0,
            null,
            null
        );

        assertThat(response.type()).isEqualTo(OnboardingProfileType.BASE);
        assertThat(response.builtIn()).isTrue();
    }

    // --- Record equality ---

    @Test
    void should_be_equal_when_all_fields_match() {
        Instant ts = Instant.parse("2026-03-01T09:00:00Z");
        Map<String, String> name = Map.of("en", "Profile");
        Map<String, String> desc = Map.of("en", "Desc");

        OnboardingProfileResponse a = new OnboardingProfileResponse(
            "id", "key", name, desc, Map.of("de", "content"),
            OnboardingProfileType.PROFILE, true, 1, 1, 2, ts, ts
        );
        OnboardingProfileResponse b = new OnboardingProfileResponse(
            "id", "key", name, desc, Map.of("de", "content"),
            OnboardingProfileType.PROFILE, true, 1, 1, 2, ts, ts
        );

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void should_not_be_equal_when_id_differs() {
        OnboardingProfileResponse a = new OnboardingProfileResponse(
            "id-a", "key", null, null, null,
            OnboardingProfileType.BASE, false, 0, 1, 0, null, null
        );
        OnboardingProfileResponse b = new OnboardingProfileResponse(
            "id-b", "key", null, null, null,
            OnboardingProfileType.BASE, false, 0, 1, 0, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_type_differs() {
        OnboardingProfileResponse a = new OnboardingProfileResponse(
            "id", "key", null, null, null,
            OnboardingProfileType.BASE, false, 0, 1, 0, null, null
        );
        OnboardingProfileResponse b = new OnboardingProfileResponse(
            "id", "key", null, null, null,
            OnboardingProfileType.PROFILE, false, 0, 1, 0, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_builtin_differs() {
        OnboardingProfileResponse a = new OnboardingProfileResponse(
            "id", "key", null, null, null,
            OnboardingProfileType.PROFILE, true, 0, 1, 0, null, null
        );
        OnboardingProfileResponse b = new OnboardingProfileResponse(
            "id", "key", null, null, null,
            OnboardingProfileType.PROFILE, false, 0, 1, 0, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_sort_order_differs() {
        OnboardingProfileResponse a = new OnboardingProfileResponse(
            "id", "key", null, null, null,
            OnboardingProfileType.BASE, false, 1, 1, 0, null, null
        );
        OnboardingProfileResponse b = new OnboardingProfileResponse(
            "id", "key", null, null, null,
            OnboardingProfileType.BASE, false, 2, 1, 0, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_include_field_names_in_toString() {
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        OnboardingProfileResponse response = new OnboardingProfileResponse(
            "profile-ts",
            "ts-key",
            Map.of("en", "TS Profile"),
            null,
            Map.of("de", "ts content"),
            OnboardingProfileType.PROFILE,
            true,
            3,
            5,
            7,
            ts,
            ts
        );

        String repr = response.toString();
        assertThat(repr).contains("id=profile-ts");
        assertThat(repr).contains("key=ts-key");
        assertThat(repr).contains("type=PROFILE");
        assertThat(repr).contains("builtIn=true");
        assertThat(repr).contains("sortOrder=3");
        assertThat(repr).contains("version=5");
        assertThat(repr).contains("historyCount=7");
    }
}

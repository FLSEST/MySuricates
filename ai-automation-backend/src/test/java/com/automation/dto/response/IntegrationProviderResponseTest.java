package com.automation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.automation.dto.response.IntegrationProviderResponse.ScopeOptionResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegrationProviderResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_expose_all_fields_when_constructed_with_full_values() {
        Instant connectedSince = Instant.parse("2026-01-15T10:30:00Z");
        ScopeOptionResponse scopeOption = new ScopeOptionResponse("read:email", "Read Email", true);
        IntegrationActionResponse action = mock(IntegrationActionResponse.class);

        IntegrationProviderResponse response = new IntegrationProviderResponse(
            "google-calendar",
            "Google Calendar",
            "calendar-icon",
            "Calendar integration",
            "CONNECTED",
            true,
            true,
            "user@example.com",
            "John Doe",
            connectedSince,
            "calendar.read calendar.write",
            List.of(scopeOption),
            List.of(action),
            true,
            "VERIFIED",
            "Google Calendar API", List.of(), true
        );

        assertThat(response.providerId()).isEqualTo("google-calendar");
        assertThat(response.displayName()).isEqualTo("Google Calendar");
        assertThat(response.icon()).isEqualTo("calendar-icon");
        assertThat(response.description()).isEqualTo("Calendar integration");
        assertThat(response.status()).isEqualTo("CONNECTED");
        assertThat(response.configured()).isTrue();
        assertThat(response.requiresCredentials()).isTrue();
        assertThat(response.accountEmail()).isEqualTo("user@example.com");
        assertThat(response.accountName()).isEqualTo("John Doe");
        assertThat(response.connectedSince()).isEqualTo(connectedSince);
        assertThat(response.scopes()).isEqualTo("calendar.read calendar.write");
        assertThat(response.optionalScopes()).containsExactly(scopeOption);
        assertThat(response.actions()).containsExactly(action);
        assertThat(response.supportsTaskExport()).isTrue();
        assertThat(response.verification()).isEqualTo("VERIFIED");
        assertThat(response.verifiedAgainst()).isEqualTo("Google Calendar API");
    }

    @Test
    void should_allow_null_optional_fields_when_not_configured() {
        IntegrationProviderResponse response = new IntegrationProviderResponse(
            "slack",
            "Slack",
            null,
            null,
            "DISCONNECTED",
            false,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            "SIMULATED",
            null, List.of(), true
        );

        assertThat(response.providerId()).isEqualTo("slack");
        assertThat(response.displayName()).isEqualTo("Slack");
        assertThat(response.icon()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.status()).isEqualTo("DISCONNECTED");
        assertThat(response.configured()).isFalse();
        assertThat(response.accountEmail()).isNull();
        assertThat(response.accountName()).isNull();
        assertThat(response.connectedSince()).isNull();
        assertThat(response.scopes()).isNull();
        assertThat(response.optionalScopes()).isNull();
        assertThat(response.actions()).isNull();
        assertThat(response.supportsTaskExport()).isFalse();
    }

    @Test
    void should_expose_requiresCredentials_false_for_local_providers() {
        IntegrationProviderResponse response = new IntegrationProviderResponse(
            "taskwarrior", "Taskwarrior", "ListTodo", "Local tasks",
            "DISCONNECTED", false, false, null, null, null, null, List.of(), List.of(), false, "SIMULATED", null, List.of(), true
        );

        assertThat(response.requiresCredentials()).isFalse();
    }

    @Test
    void should_handle_empty_lists_when_no_scopes_or_actions() {
        IntegrationProviderResponse response = new IntegrationProviderResponse(
            "trello",
            "Trello",
            "trello-icon",
            "Trello integration",
            "CONNECTED",
            true,
            true,
            "user@example.com",
            "Jane",
            Instant.now(),
            "",
            List.of(),
            List.of(),
            false,
            "SIMULATED",
            null, List.of(), true
        );

        assertThat(response.optionalScopes()).isNotNull().isEmpty();
        assertThat(response.actions()).isNotNull().isEmpty();
        assertThat(response.scopes()).isEmpty();
    }

    // --- Record equality ---

    @Test
    void should_be_equal_when_all_fields_match() {
        Instant ts = Instant.parse("2026-02-01T08:00:00Z");
        IntegrationProviderResponse a = new IntegrationProviderResponse(
            "id", "name", "icon", "desc", "OK", true, true,
            "e@x", "n", ts, "s", List.of(), List.of(), false, "SIMULATED", null, List.of(), true
        );
        IntegrationProviderResponse b = new IntegrationProviderResponse(
            "id", "name", "icon", "desc", "OK", true, true,
            "e@x", "n", ts, "s", List.of(), List.of(), false, "SIMULATED", null, List.of(), true
        );

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void should_not_be_equal_when_provider_id_differs() {
        IntegrationProviderResponse a = new IntegrationProviderResponse(
            "id-a", "name", null, null, "OK", true, true, null, null, null, null, null, null, false, "SIMULATED", null, List.of(), true
        );
        IntegrationProviderResponse b = new IntegrationProviderResponse(
            "id-b", "name", null, null, "OK", true, true, null, null, null, null, null, null, false, "SIMULATED", null, List.of(), true
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_configured_differs() {
        IntegrationProviderResponse a = new IntegrationProviderResponse(
            "id", "name", null, null, "OK", true, true, null, null, null, null, null, null, false, "SIMULATED", null, List.of(), true
        );
        IntegrationProviderResponse b = new IntegrationProviderResponse(
            "id", "name", null, null, "OK", false, true, null, null, null, null, null, null, false, "SIMULATED", null, List.of(), true
        );

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_include_field_names_in_toString() {
        IntegrationProviderResponse response = new IntegrationProviderResponse(
            "github", "GitHub", null, null, "CONNECTED", true, true,
            "u@x", "U", null, null, null, null, true, "SIMULATED", null, List.of(), true
        );

        String repr = response.toString();
        assertThat(repr).contains("providerId=github");
        assertThat(repr).contains("displayName=GitHub");
        assertThat(repr).contains("status=CONNECTED");
        assertThat(repr).contains("configured=true");
    }

    // --- Nested ScopeOptionResponse ---

    @Test
    void should_expose_scope_option_fields_when_constructed() {
        ScopeOptionResponse option = new ScopeOptionResponse("write:repo", "Write Repository", true);

        assertThat(option.scope()).isEqualTo("write:repo");
        assertThat(option.label()).isEqualTo("Write Repository");
        assertThat(option.granted()).isTrue();
    }

    @Test
    void should_handle_not_granted_scope_option() {
        ScopeOptionResponse option = new ScopeOptionResponse("admin:org", "Admin", false);

        assertThat(option.scope()).isEqualTo("admin:org");
        assertThat(option.label()).isEqualTo("Admin");
        assertThat(option.granted()).isFalse();
    }

    @Test
    void should_allow_null_strings_in_scope_option() {
        ScopeOptionResponse option = new ScopeOptionResponse(null, null, false);

        assertThat(option.scope()).isNull();
        assertThat(option.label()).isNull();
        assertThat(option.granted()).isFalse();
    }

    @Test
    void scope_option_should_be_equal_when_all_fields_match() {
        ScopeOptionResponse a = new ScopeOptionResponse("s", "l", true);
        ScopeOptionResponse b = new ScopeOptionResponse("s", "l", true);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void scope_option_should_not_be_equal_when_granted_differs() {
        ScopeOptionResponse a = new ScopeOptionResponse("s", "l", true);
        ScopeOptionResponse b = new ScopeOptionResponse("s", "l", false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void scope_option_toString_should_contain_field_names() {
        ScopeOptionResponse option = new ScopeOptionResponse("read", "Read access", true);

        String repr = option.toString();
        assertThat(repr).contains("scope=read");
        assertThat(repr).contains("label=Read access");
        assertThat(repr).contains("granted=true");
    }
}

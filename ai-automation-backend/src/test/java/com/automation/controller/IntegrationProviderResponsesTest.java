package com.automation.controller;

import com.automation.dto.response.IntegrationActionResponse;
import com.automation.dto.response.IntegrationConnectionResponse;
import com.automation.dto.response.IntegrationProviderResponse;
import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider-Karte und Verbindungseinträge: Status der default-Verbindung, Konfiguriert-Regel, Scopes, Konto. */
class IntegrationProviderResponsesTest {

    private final IntegrationProviderResponses responses = new IntegrationProviderResponses(new ObjectMapper());

    @Test
    void provider_withoutAnyConnection_isDisconnectedAndListsOptionalScopesUngranted() {
        IntegrationProviderResponse card = responses.provider(new StubProvider(true), List.of(), false);

        assertThat(card.providerId()).isEqualTo("stub");
        assertThat(card.status()).isEqualTo("DISCONNECTED");
        assertThat(card.configured()).isFalse();
        assertThat(card.accountEmail()).isNull();
        assertThat(card.scopes()).isNull();
        assertThat(card.connections()).isEmpty();
        assertThat(card.optionalScopes()).extracting(IntegrationProviderResponse.ScopeOptionResponse::granted)
                .containsOnly(false);
        assertThat(card.verification()).isEqualTo("SIMULATED");
        assertThat(card.requiresOAuthFlow()).isTrue();
    }

    @Test
    void provider_topLevelFieldsDescribeTheDefaultConnection_notTheNamedOnes() {
        IntegrationConnection work = connection("work", ConnectionStatus.EXPIRED, null, "read write");
        IntegrationConnection def = connection("default", ConnectionStatus.CONNECTED,
                "{\"email\":\"a@b.de\",\"name\":\"Anna\"}", "read");

        IntegrationProviderResponse card = responses.provider(new StubProvider(true), List.of(work, def), true);

        assertThat(card.status()).isEqualTo("CONNECTED");
        assertThat(card.accountEmail()).isEqualTo("a@b.de");
        assertThat(card.accountName()).isEqualTo("Anna");
        assertThat(card.scopes()).isEqualTo("read");
        assertThat(card.connectedSince()).isEqualTo(def.getCreatedAt());
        assertThat(card.connections()).extracting(IntegrationConnectionResponse::name)
                .containsExactly("work", "default");
        assertThat(card.optionalScopes()).extracting(IntegrationProviderResponse.ScopeOptionResponse::scope,
                        IntegrationProviderResponse.ScopeOptionResponse::granted)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("write", false),
                        org.assertj.core.groups.Tuple.tuple("read", true));
    }

    @Test
    void actions_areAvailableOnlyWithTheirRequiredScopes_orWhenScopesAreUnknown() {
        StubProvider provider = new StubProvider(true);

        assertThat(responses.actions(provider, "read"))
                .extracting(IntegrationActionResponse::name, IntegrationActionResponse::available)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("list", true),
                        org.assertj.core.groups.Tuple.tuple("create", false));
        assertThat(responses.actions(provider, "read write"))
                .extracting(IntegrationActionResponse::available).containsOnly(true);
        assertThat(responses.actions(provider, null))
                .extracting(IntegrationActionResponse::available).containsOnly(true);
    }

    @Test
    void connection_isConfiguredByOwnCredentials() {
        IntegrationConnection own = connection("work", ConnectionStatus.CONNECTED, null, null);
        own.setClientIdEncrypted("id");
        own.setClientSecretEncrypted("secret");
        IntegrationConnection half = connection("half", ConnectionStatus.CONNECTED, null, null);
        half.setClientIdEncrypted("id");

        assertThat(responses.connection(new StubProvider(false), own, false).configured()).isTrue();
        assertThat(responses.connection(new StubProvider(false), half, false).configured()).isFalse();
    }

    @Test
    void connection_oauthProvidersShareTheDefaultApp_othersDoNot() {
        IntegrationConnection second = connection("work", ConnectionStatus.DISCONNECTED, null, null);
        IntegrationConnection def = connection("default", ConnectionStatus.DISCONNECTED, null, null);

        assertThat(responses.connection(new StubProvider(true), second, true).configured()).isTrue();
        assertThat(responses.connection(new StubProvider(true), second, false).configured()).isFalse();
        assertThat(responses.connection(new StubProvider(false), second, true).configured()).isFalse();
        assertThat(responses.connection(new StubProvider(false), def, true).configured()).isTrue();
        assertThat(responses.connection(new StubProvider(false), def, false).configured()).isFalse();
    }

    @Test
    void connection_brokenOrPartialAccountInfo_doesNotBreakTheResponse() {
        IntegrationConnection broken = connection("a", ConnectionStatus.CONNECTED, "{nicht json", null);
        IntegrationConnection partial = connection("b", ConnectionStatus.CONNECTED, "{\"name\":\"Nur Name\"}", null);

        IntegrationConnectionResponse brokenResponse = responses.connection(new StubProvider(true), broken, false);
        IntegrationConnectionResponse partialResponse = responses.connection(new StubProvider(true), partial, false);

        assertThat(brokenResponse.accountEmail()).isNull();
        assertThat(brokenResponse.accountName()).isNull();
        assertThat(brokenResponse.status()).isEqualTo("CONNECTED");
        assertThat(partialResponse.accountEmail()).isNull();
        assertThat(partialResponse.accountName()).isEqualTo("Nur Name");
    }

    @Test
    void parseScopes_splitsOnWhitespace_andToleratesEmptyInput() {
        assertThat(IntegrationProviderResponses.parseScopes("  a   b\tc ")).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(IntegrationProviderResponses.parseScopes(null)).isEmpty();
        assertThat(IntegrationProviderResponses.parseScopes("   ")).isEmpty();
    }

    private static IntegrationConnection connection(String name, ConnectionStatus status, String accountInfo,
                                                    String scopes) {
        return IntegrationConnection.builder().providerId("stub").connectionName(name).status(status)
                .accountInfo(accountInfo).scopes(scopes).createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
    }

    private record StubProvider(boolean oauth) implements IntegrationProvider {
        @Override public String getProviderId() { return "stub"; }
        @Override public String getDisplayName() { return "Stub"; }
        @Override public String getIcon() { return "plug"; }
        @Override public String getDescription() { return "Testprovider"; }
        @Override public boolean requiresOAuthFlow() { return oauth; }
        @Override public OAuthConfig getOAuthConfig() {
            return new OAuthConfig("https://auth", "https://token", List.of("read"), List.of("write", "read"),
                    "ID", "SECRET", Map.of());
        }
        @Override public List<IntegrationAction> getAvailableActions() {
            return List.of(new IntegrationAction("list", "Listet", Map.of()),
                    new IntegrationAction("create", "Legt an", Map.of(), List.of("read", "write")));
        }
        @Override public Object executeAction(String action, Map<String, Object> params, String accessToken) {
            return null;
        }
    }
}

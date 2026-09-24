package com.automation.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Every shipped provider states honestly how far it was checked: VERIFIED names what it was
 * checked against, SIMULATED names nothing, and the {@link VerifiedProviders} ledger only lists
 * providers that exist. Finds the providers by classpath scan, so a new provider is covered
 * without being listed here.
 */
class IntegrationVerificationGateTest {

    static List<IntegrationProvider> shippedProviders() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<IntegrationProvider> providers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.automation.service.integration.providers")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (!IntegrationProvider.class.isAssignableFrom(type)) continue;
            providers.add((IntegrationProvider) instantiate(type));
        }
        return providers;
    }

    private static Object instantiate(Class<?> type) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
        ctor.setAccessible(true);
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(p -> p == ObjectMapper.class ? new ObjectMapper() : mock(p))
                .toArray();
        return ctor.newInstance(args);
    }

    @Test
    void scanFindsTheShippedProviders() throws Exception {
        assertThat(shippedProviders()).extracting(IntegrationProvider::getProviderId)
                .contains("github", "jira", "trello", "google-calendar", "todoist", "slack");
    }

    @Test
    void verifiedProvidersNameWhatTheyWereCheckedAgainst() throws Exception {
        for (IntegrationProvider p : shippedProviders()) {
            if (p.getVerification() == IntegrationVerification.VERIFIED) {
                assertThat(p.getVerifiedAgainst())
                        .as("%s is VERIFIED and must say what it was checked against", p.getProviderId())
                        .isNotBlank();
            } else {
                assertThat(p.getVerifiedAgainst())
                        .as("%s is SIMULATED and must not claim a verification target", p.getProviderId())
                        .isNull();
            }
        }
    }

    @Test
    void ledgerOnlyNamesShippedProviders() throws Exception {
        var shippedIds = shippedProviders().stream().map(IntegrationProvider::getProviderId).toList();
        assertThat(shippedIds).as("every VerifiedProviders entry must name a shipped provider")
                .containsAll(VerifiedProviders.providerIds());
    }

    @Test
    void providersWithoutRealAccountStaySimulated() throws Exception {
        for (IntegrationProvider p : shippedProviders()) {
            if (java.util.Set.of("todoist", "slack", "telegram").contains(p.getProviderId())) {
                assertThat(p.getVerification()).as("%s was only tested against simulated responses", p.getProviderId())
                        .isEqualTo(IntegrationVerification.SIMULATED);
            }
        }
    }

    @Test
    void newProvidersDefaultToSimulated() {
        IntegrationProvider bare = new IntegrationProvider() {
            @Override public String getProviderId() { return "bare"; }
            @Override public String getDisplayName() { return "Bare"; }
            @Override public String getIcon() { return "Plug"; }
            @Override public String getDescription() { return "Test"; }
            @Override public OAuthConfig getOAuthConfig() { return new OAuthConfig("", "", List.of(), "", ""); }
            @Override public List<IntegrationAction> getAvailableActions() { return List.of(); }
            @Override public Object executeAction(String action, java.util.Map<String, Object> params, String accessToken) { return null; }
        };

        assertThat(bare.getVerification()).isEqualTo(IntegrationVerification.SIMULATED);
        assertThat(bare.getVerifiedAgainst()).isNull();
    }
}

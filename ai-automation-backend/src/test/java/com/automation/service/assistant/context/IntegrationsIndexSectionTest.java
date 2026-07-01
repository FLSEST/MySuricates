package com.automation.service.assistant.context;

import com.automation.entity.IntegrationConnection;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationsIndexSectionTest {

    @Mock private IntegrationRegistry integrationRegistry;
    @Mock private IntegrationConnectionRepository connectionRepository;

    private IntegrationsIndexSection section;

    @BeforeEach
    void setUp() {
        section = new IntegrationsIndexSection(integrationRegistry, connectionRepository);
    }

    private IntegrationProvider provider(String id, String name, String description) {
        IntegrationProvider p = mock(IntegrationProvider.class);
        lenient().when(p.getProviderId()).thenReturn(id);
        lenient().when(p.getDisplayName()).thenReturn(name);
        lenient().when(p.getDescription()).thenReturn(description);
        return p;
    }

    private IntegrationConnection connection(String providerId, IntegrationConnection.ConnectionStatus status) {
        return IntegrationConnection.builder().providerId(providerId).status(status).build();
    }

    @Test
    void compact_showsProviderAndConnectedCount() {
        IntegrationProvider github = provider("github", "GitHub", "Issues, PRs");
        IntegrationProvider slack = provider("slack", "Slack", "Messages, Channels");
        IntegrationProvider jira = provider("jira", "Jira", "Tickets");
        when(integrationRegistry.getAllProviders()).thenReturn(List.of(github, slack, jira));
        when(connectionRepository.findAll()).thenReturn(List.of(
                connection("github", IntegrationConnection.ConnectionStatus.CONNECTED),
                connection("jira", IntegrationConnection.ConnectionStatus.DISCONNECTED)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).contains("**Integrations:** 3 providers (1 connected)");
    }

    @Test
    void compact_noProviders_appendsNothing() {
        when(integrationRegistry.getAllProviders()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsProvidersWithConnectionMarkers() {
        IntegrationProvider github = provider("github", "GitHub", "Issues, PRs");
        IntegrationProvider slack = provider("slack", "Slack", "Messages, Channels");
        when(integrationRegistry.getAllProviders()).thenReturn(List.of(github, slack));
        when(connectionRepository.findAll()).thenReturn(List.of(
                connection("github", IntegrationConnection.ConnectionStatus.CONNECTED)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("**Integrations (2):**")
                .contains("[✓] GitHub")
                .contains("[✗] Slack")
                .contains("<fetch type=\"integration\"");
    }

    @Test
    void full_connectionRepositoryFails_stillShowsProviders() {
        IntegrationProvider github = provider("github", "GitHub", "Issues, PRs");
        when(integrationRegistry.getAllProviders()).thenReturn(List.of(github));
        when(connectionRepository.findAll()).thenThrow(new RuntimeException("db down"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).contains("[✗] GitHub");
    }

    @Test
    void exception_doesNotThrow() {
        when(integrationRegistry.getAllProviders()).thenThrow(new RuntimeException("boom"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }
}

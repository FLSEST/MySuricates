package com.automation.repository;

import com.automation.entity.IntegrationConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class IntegrationConnectionRepositoryTest {

    @Autowired
    private IntegrationConnectionRepository repository;

    @Test
    void findByProviderId_returnsConnection_whenProviderExists() {
        String providerId = "google-drive-" + System.nanoTime();
        IntegrationConnection saved = persistConnection(providerId);

        Optional<IntegrationConnection> found = repository.findByProviderIdAndConnectionName(providerId, "default");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getProviderId()).isEqualTo(providerId);
        assertThat(found.get().getStatus()).isEqualTo(IntegrationConnection.ConnectionStatus.DISCONNECTED);
    }

    @Test
    void findByProviderId_returnsEmpty_whenProviderDoesNotExist() {
        Optional<IntegrationConnection> found = repository.findByProviderIdAndConnectionName("non-existent-" + System.nanoTime(), "default");

        assertThat(found).isEmpty();
    }

    @Test
    void findByProviderId_returnsCorrectConnection_whenMultipleExist() {
        String providerA = "slack-" + System.nanoTime();
        String providerB = "github-" + System.nanoTime();
        persistConnection(providerA);
        IntegrationConnection savedB = persistConnection(providerB);

        Optional<IntegrationConnection> found = repository.findByProviderIdAndConnectionName(providerB, "default");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(savedB.getId());
        assertThat(found.get().getProviderId()).isEqualTo(providerB);
    }

    @Test
    void deleteByProviderId_removesConnection_whenProviderExists() {
        String providerId = "jira-" + System.nanoTime();
        persistConnection(providerId);
        assertThat(repository.findByProviderIdAndConnectionName(providerId, "default")).isPresent();

        repository.deleteByProviderId(providerId);
        repository.flush();

        assertThat(repository.findByProviderIdAndConnectionName(providerId, "default")).isEmpty();
    }

    @Test
    void deleteByProviderId_doesNothing_whenProviderDoesNotExist() {
        String existing = "trello-" + System.nanoTime();
        persistConnection(existing);
        long countBefore = repository.count();

        repository.deleteByProviderId("missing-" + System.nanoTime());
        repository.flush();

        assertThat(repository.count()).isEqualTo(countBefore);
        assertThat(repository.findByProviderIdAndConnectionName(existing, "default")).isPresent();
    }

    @Test
    void deleteByProviderId_onlyRemovesMatchingConnection() {
        String providerToDelete = "asana-" + System.nanoTime();
        String providerToKeep = "notion-" + System.nanoTime();
        persistConnection(providerToDelete);
        persistConnection(providerToKeep);

        repository.deleteByProviderId(providerToDelete);
        repository.flush();

        assertThat(repository.findByProviderIdAndConnectionName(providerToDelete, "default")).isEmpty();
        assertThat(repository.findByProviderIdAndConnectionName(providerToKeep, "default")).isPresent();
    }

    // ── Named connections (V86, 0b3cbcc3) ─────────────────────────────────────

    @Test
    void namedConnections_coexistPerProvider_andAreFoundByName() {
        String providerId = "github-" + System.nanoTime();
        IntegrationConnection standard = persistConnection(providerId);
        IntegrationConnection work = persistConnection(providerId, "work");

        assertThat(standard.getConnectionName()).isEqualTo("default");
        assertThat(repository.findByProviderIdAndConnectionName(providerId, "work"))
                .get().extracting(IntegrationConnection::getId).isEqualTo(work.getId());
        assertThat(repository.findByProviderIdAndConnectionName(providerId, "default"))
                .get().extracting(IntegrationConnection::getId).isEqualTo(standard.getId());
        assertThat(repository.findAllByProviderId(providerId))
                .extracting(IntegrationConnection::getConnectionName)
                .containsExactlyInAnyOrder("default", "work");
    }

    @Test
    void connectionName_isUniqueWithinAProvider() {
        String providerId = "jira-" + System.nanoTime();
        persistConnection(providerId, "work");

        assertThatThrownBy(() -> persistConnection(providerId, "work"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE");
    }

    private IntegrationConnection persistConnection(String providerId, String name) {
        return repository.saveAndFlush(IntegrationConnection.builder()
                .providerId(providerId)
                .connectionName(name)
                .status(IntegrationConnection.ConnectionStatus.DISCONNECTED)
                .build());
    }

    private IntegrationConnection persistConnection(String providerId) {
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId(providerId)
                .status(IntegrationConnection.ConnectionStatus.DISCONNECTED)
                .build();
        return repository.saveAndFlush(connection);
    }
}

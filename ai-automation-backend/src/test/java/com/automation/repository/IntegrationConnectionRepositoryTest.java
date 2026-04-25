package com.automation.repository;

import com.automation.entity.IntegrationConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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

        Optional<IntegrationConnection> found = repository.findByProviderId(providerId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getProviderId()).isEqualTo(providerId);
        assertThat(found.get().getStatus()).isEqualTo(IntegrationConnection.ConnectionStatus.DISCONNECTED);
    }

    @Test
    void findByProviderId_returnsEmpty_whenProviderDoesNotExist() {
        Optional<IntegrationConnection> found = repository.findByProviderId("non-existent-" + System.nanoTime());

        assertThat(found).isEmpty();
    }

    @Test
    void findByProviderId_returnsCorrectConnection_whenMultipleExist() {
        String providerA = "slack-" + System.nanoTime();
        String providerB = "github-" + System.nanoTime();
        persistConnection(providerA);
        IntegrationConnection savedB = persistConnection(providerB);

        Optional<IntegrationConnection> found = repository.findByProviderId(providerB);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(savedB.getId());
        assertThat(found.get().getProviderId()).isEqualTo(providerB);
    }

    @Test
    void deleteByProviderId_removesConnection_whenProviderExists() {
        String providerId = "jira-" + System.nanoTime();
        persistConnection(providerId);
        assertThat(repository.findByProviderId(providerId)).isPresent();

        repository.deleteByProviderId(providerId);
        repository.flush();

        assertThat(repository.findByProviderId(providerId)).isEmpty();
    }

    @Test
    void deleteByProviderId_doesNothing_whenProviderDoesNotExist() {
        String existing = "trello-" + System.nanoTime();
        persistConnection(existing);
        long countBefore = repository.count();

        repository.deleteByProviderId("missing-" + System.nanoTime());
        repository.flush();

        assertThat(repository.count()).isEqualTo(countBefore);
        assertThat(repository.findByProviderId(existing)).isPresent();
    }

    @Test
    void deleteByProviderId_onlyRemovesMatchingConnection() {
        String providerToDelete = "asana-" + System.nanoTime();
        String providerToKeep = "notion-" + System.nanoTime();
        persistConnection(providerToDelete);
        persistConnection(providerToKeep);

        repository.deleteByProviderId(providerToDelete);
        repository.flush();

        assertThat(repository.findByProviderId(providerToDelete)).isEmpty();
        assertThat(repository.findByProviderId(providerToKeep)).isPresent();
    }

    private IntegrationConnection persistConnection(String providerId) {
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId(providerId)
                .status(IntegrationConnection.ConnectionStatus.DISCONNECTED)
                .build();
        return repository.saveAndFlush(connection);
    }
}

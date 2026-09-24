package com.automation.repository;

import com.automation.entity.IntegrationConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, String> {

    /** One named connection of a provider — {@code (provider_id, connection_name)} is unique (V86). */
    Optional<IntegrationConnection> findByProviderIdAndConnectionName(String providerId, String connectionName);

    /** Every connection of a provider, the {@code default} one included. */
    List<IntegrationConnection> findAllByProviderId(String providerId);

    /** Removes every connection of a provider. */
    void deleteByProviderId(String providerId);
}

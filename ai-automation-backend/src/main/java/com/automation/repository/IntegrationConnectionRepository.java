package com.automation.repository;

import com.automation.entity.IntegrationConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, String> {

    Optional<IntegrationConnection> findByProviderId(String providerId);

    void deleteByProviderId(String providerId);
}

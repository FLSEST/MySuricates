package com.automation.repository;

import com.automation.entity.WebhookTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookTriggerRepository extends JpaRepository<WebhookTrigger, String> {

    Optional<WebhookTrigger> findBySecretToken(String secretToken);

    List<WebhookTrigger> findByWorkflowId(String workflowId);

    long countByEnabledTrue();

    @Modifying
    @Query("UPDATE WebhookTrigger w SET w.triggerCount = w.triggerCount + 1, w.lastTriggeredAt = :timestamp WHERE w.id = :id")
    void incrementTriggerCount(@Param("id") String id, @Param("timestamp") Instant timestamp);
}

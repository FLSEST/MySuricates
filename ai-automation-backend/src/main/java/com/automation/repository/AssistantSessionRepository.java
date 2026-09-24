package com.automation.repository;

import com.automation.entity.AssistantSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AssistantSessionRepository extends JpaRepository<AssistantSession, String> {

    List<AssistantSession> findTop10ByPageOrderByStartedAtDesc(String page);

    List<AssistantSession> findTop3ByOrderByStartedAtDesc();

    List<AssistantSession> findTop5ByOrderByStartedAtDesc();

    long countByPage(String page);

    /** Durchschnittliche Nachrichtenzahl je Session; {@code null} ohne Sessions. */
    @Query("select avg(s.messageCount) from AssistantSession s")
    Double averageMessageCount();

    List<AssistantSession> findTop50ByOrderByStartedAtDesc();
}

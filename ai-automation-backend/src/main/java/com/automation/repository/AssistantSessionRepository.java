package com.automation.repository;

import com.automation.entity.AssistantSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantSessionRepository extends JpaRepository<AssistantSession, String> {

    List<AssistantSession> findTop10ByPageOrderByStartedAtDesc(String page);

    List<AssistantSession> findTop3ByOrderByStartedAtDesc();

    List<AssistantSession> findTop5ByOrderByStartedAtDesc();

    long countByPage(String page);

    List<AssistantSession> findTop50ByOrderByStartedAtDesc();
}

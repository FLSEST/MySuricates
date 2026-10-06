package com.automation.repository;

import com.automation.entity.RunLiveLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RunLiveLogRepository extends JpaRepository<RunLiveLog, String> {
}

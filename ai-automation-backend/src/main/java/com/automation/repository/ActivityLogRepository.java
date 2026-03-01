package com.automation.repository;

import com.automation.entity.ActivityLog;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, String> {

    Page<ActivityLog> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<ActivityLog> findByLevelOrderByTimestampDesc(LogLevel level, Pageable pageable);

    Page<ActivityLog> findByCategoryOrderByTimestampDesc(LogCategory category, Pageable pageable);

    Page<ActivityLog> findByLevelAndCategoryOrderByTimestampDesc(LogLevel level, LogCategory category, Pageable pageable);

    Page<ActivityLog> findByWorkflowIdOrderByTimestampDesc(String workflowId, Pageable pageable);

    Page<ActivityLog> findByWorkflowIdAndLevelOrderByTimestampDesc(String workflowId, LogLevel level, Pageable pageable);

    Page<ActivityLog> findByWorkflowIdAndCategoryOrderByTimestampDesc(String workflowId, LogCategory category, Pageable pageable);

    Page<ActivityLog> findByWorkflowIdAndLevelAndCategoryOrderByTimestampDesc(String workflowId, LogLevel level, LogCategory category, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ActivityLog a WHERE a.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);

    @Modifying
    @Query("DELETE FROM ActivityLog a WHERE a.level IN :levels AND a.createdAt < :before")
    int deleteByLevelsOlderThan(@Param("levels") java.util.Collection<LogLevel> levels, @Param("before") Instant before);
}

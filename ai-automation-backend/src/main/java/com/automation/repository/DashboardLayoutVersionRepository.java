package com.automation.repository;

import com.automation.dto.response.DashboardLayoutVersionListItem;
import com.automation.entity.DashboardLayoutVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DashboardLayoutVersionRepository extends JpaRepository<DashboardLayoutVersion, String> {

    Page<DashboardLayoutVersionListItem> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Optional<DashboardLayoutVersionListItem> findFirstByOrderByCreatedAtDescIdDesc();

    /** Deletes unnamed versions beyond the newest {@code keep} unnamed ones; named versions always stay. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM dashboard_layout_versions
            WHERE name IS NULL
              AND id NOT IN (SELECT id FROM dashboard_layout_versions
                             WHERE name IS NULL
                             ORDER BY created_at DESC, id DESC
                             LIMIT :keep)
            """, nativeQuery = true)
    int pruneUnnamed(@Param("keep") int keep);
}

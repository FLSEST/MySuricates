package com.automation.repository;

import com.automation.entity.RequestMetricDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RequestMetricDailyRepository extends JpaRepository<RequestMetricDaily, String> {

    Optional<RequestMetricDaily> findByDayAndRoute(LocalDate day, String route);

    List<RequestMetricDaily> findByDayOrderByP95MsDesc(LocalDate day);

    /** Baseline window for one route — the days before the one being judged. */
    List<RequestMetricDaily> findByRouteAndDayBetweenOrderByDayAsc(String route, LocalDate from, LocalDate to);

    /**
     * Baseline window for all routes in one query — chronological, so the rows of each route keep
     * day order after filtering. The drift watchdog used one query per route before and held the
     * single connection for about 70 s (2026-09-24).
     */
    List<RequestMetricDaily> findByDayBetweenOrderByDayAsc(LocalDate from, LocalDate to);

    @Modifying
    @Query("DELETE FROM RequestMetricDaily m WHERE m.day < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDate cutoff);
}

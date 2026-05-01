package com.automation.repository;

import com.automation.entity.DeadLetterEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterEntry, String> {
    Page<DeadLetterEntry> findByStatusOrderByLastFailureAtDesc(DeadLetterEntry.Status status, Pageable pageable);
    Page<DeadLetterEntry> findAllByOrderByLastFailureAtDesc(Pageable pageable);
}

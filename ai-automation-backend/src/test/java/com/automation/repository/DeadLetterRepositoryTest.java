package com.automation.repository;

import com.automation.entity.DeadLetterEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class DeadLetterRepositoryTest {

    @Autowired
    private DeadLetterRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private DeadLetterEntry entry(String workflowName, DeadLetterEntry.Status status, Instant lastFailureAt) {
        return DeadLetterEntry.builder()
                .workflowRunId("run-" + workflowName)
                .workflowId("wf-" + workflowName)
                .workflowName(workflowName)
                .errorMessage("error for " + workflowName)
                .failedStepIndex(0)
                .retryCount(0)
                .lastFailureAt(lastFailureAt)
                .status(status)
                .build();
    }

    @Test
    void should_persist_and_findById_when_save_called() {
        DeadLetterEntry saved = repository.save(entry("wf-a", DeadLetterEntry.Status.OPEN, Instant.now()));

        assertThat(saved.getId()).isNotBlank();
        assertThat(repository.findById(saved.getId())).isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.getWorkflowName()).isEqualTo("wf-a");
                    assertThat(found.getStatus()).isEqualTo(DeadLetterEntry.Status.OPEN);
                    assertThat(found.getErrorMessage()).isEqualTo("error for wf-a");
                });
    }

    @Test
    void should_return_all_saved_entries_when_findAll_called() {
        repository.save(entry("wf-a", DeadLetterEntry.Status.OPEN, Instant.now()));
        repository.save(entry("wf-b", DeadLetterEntry.Status.RETRIED, Instant.now()));
        repository.save(entry("wf-c", DeadLetterEntry.Status.DISCARDED, Instant.now()));

        assertThat(repository.findAll()).hasSize(3);
    }

    @Test
    void should_return_entries_ordered_by_lastFailureAt_desc_when_findAllByOrderByLastFailureAtDesc_called() {
        Instant now = Instant.now();
        repository.save(entry("oldest", DeadLetterEntry.Status.OPEN, now.minus(2, ChronoUnit.HOURS)));
        repository.save(entry("newest", DeadLetterEntry.Status.OPEN, now));
        repository.save(entry("middle", DeadLetterEntry.Status.OPEN, now.minus(1, ChronoUnit.HOURS)));

        Page<DeadLetterEntry> page = repository.findAllByOrderByLastFailureAtDesc(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(DeadLetterEntry::getWorkflowName)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    void should_filter_by_status_and_order_desc_when_findByStatusOrderByLastFailureAtDesc_called() {
        Instant now = Instant.now();
        repository.save(entry("open-old", DeadLetterEntry.Status.OPEN, now.minus(2, ChronoUnit.HOURS)));
        repository.save(entry("open-new", DeadLetterEntry.Status.OPEN, now));
        repository.save(entry("retried", DeadLetterEntry.Status.RETRIED, now.minus(1, ChronoUnit.HOURS)));
        repository.save(entry("discarded", DeadLetterEntry.Status.DISCARDED, now.minus(3, ChronoUnit.HOURS)));

        Page<DeadLetterEntry> openPage = repository.findByStatusOrderByLastFailureAtDesc(
                DeadLetterEntry.Status.OPEN, PageRequest.of(0, 10));

        assertThat(openPage.getTotalElements()).isEqualTo(2);
        assertThat(openPage.getContent())
                .extracting(DeadLetterEntry::getWorkflowName)
                .containsExactly("open-new", "open-old");
    }

    @Test
    void should_return_empty_page_when_no_entries_match_status() {
        repository.save(entry("open-1", DeadLetterEntry.Status.OPEN, Instant.now()));

        Page<DeadLetterEntry> discardedPage = repository.findByStatusOrderByLastFailureAtDesc(
                DeadLetterEntry.Status.DISCARDED, PageRequest.of(0, 10));

        assertThat(discardedPage.getTotalElements()).isZero();
        assertThat(discardedPage.getContent()).isEmpty();
    }

    @Test
    void should_paginate_results_when_pageable_provided() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            repository.save(entry("wf-" + i, DeadLetterEntry.Status.OPEN, now.minus(i, ChronoUnit.MINUTES)));
        }

        Page<DeadLetterEntry> firstPage = repository.findAllByOrderByLastFailureAtDesc(PageRequest.of(0, 2));
        Page<DeadLetterEntry> secondPage = repository.findAllByOrderByLastFailureAtDesc(PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(2);
        assertThat(firstPage.getContent()).doesNotContainAnyElementsOf(secondPage.getContent());
    }

    @Test
    void should_remove_entry_when_delete_called() {
        DeadLetterEntry saved = repository.save(entry("wf-delete", DeadLetterEntry.Status.OPEN, Instant.now()));

        repository.deleteById(saved.getId());

        assertThat(repository.findById(saved.getId())).isEmpty();
    }

    @Test
    void should_filter_by_RETRIED_status_when_findByStatusOrderByLastFailureAtDesc_called() {
        repository.save(entry("wf-open", DeadLetterEntry.Status.OPEN, Instant.parse("2025-01-01T10:00:00Z")));
        repository.save(entry("wf-retried-old", DeadLetterEntry.Status.RETRIED, Instant.parse("2025-01-02T10:00:00Z")));
        repository.save(entry("wf-retried-new", DeadLetterEntry.Status.RETRIED, Instant.parse("2025-01-03T10:00:00Z")));
        repository.save(entry("wf-discarded", DeadLetterEntry.Status.DISCARDED, Instant.parse("2025-01-04T10:00:00Z")));

        Page<DeadLetterEntry> page = repository.findByStatusOrderByLastFailureAtDesc(
                DeadLetterEntry.Status.RETRIED, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(DeadLetterEntry::getWorkflowName)
                .containsExactly("wf-retried-new", "wf-retried-old");
    }

    @Test
    void should_return_empty_page_when_findAllByOrderByLastFailureAtDesc_called_on_empty_repository() {
        Page<DeadLetterEntry> page = repository.findAllByOrderByLastFailureAtDesc(PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void should_paginate_filtered_results_when_findByStatusOrderByLastFailureAtDesc_with_pageable() {
        for (int i = 0; i < 5; i++) {
            repository.save(entry("wf-open-" + i, DeadLetterEntry.Status.OPEN,
                    Instant.parse("2025-01-0" + (i + 1) + "T10:00:00Z")));
        }
        repository.save(entry("wf-retried", DeadLetterEntry.Status.RETRIED, Instant.parse("2025-01-10T10:00:00Z")));

        Page<DeadLetterEntry> firstPage = repository.findByStatusOrderByLastFailureAtDesc(
                DeadLetterEntry.Status.OPEN, PageRequest.of(0, 2));
        Page<DeadLetterEntry> secondPage = repository.findByStatusOrderByLastFailureAtDesc(
                DeadLetterEntry.Status.OPEN, PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent())
                .extracting(DeadLetterEntry::getWorkflowName)
                .containsExactly("wf-open-4", "wf-open-3");
        assertThat(secondPage.getContent())
                .extracting(DeadLetterEntry::getWorkflowName)
                .containsExactly("wf-open-2", "wf-open-1");
    }
}

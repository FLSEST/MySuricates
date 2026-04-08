package com.automation.repository;

import com.automation.entity.ActivityLog;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ActivityLogRepositoryTest {

    @Autowired
    private ActivityLogRepository repository;

    private ActivityLog entry(LogLevel level, LogCategory category) {
        return ActivityLog.builder()
                .timestamp(Instant.now())
                .level(level)
                .category(category)
                .message("Test: " + level + "/" + category)
                .build();
    }

    private ActivityLog entryWithWorkflow(LogLevel level, LogCategory category, String workflowId) {
        return ActivityLog.builder()
                .timestamp(Instant.now())
                .level(level)
                .category(category)
                .message("WF log")
                .workflowId(workflowId)
                .build();
    }

    // --- findAll ---

    @Test
    void findAllByOrderByTimestampDesc_returnsAllSavedEntries() {
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.ERROR, LogCategory.EXECUTION));

        Page<ActivityLog> page = repository.findAllByOrderByTimestampDesc(PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void findAllByOrderByTimestampDesc_orderedNewestFirst() {
        Instant older = Instant.now().minusSeconds(10);
        Instant newer = Instant.now();

        repository.save(ActivityLog.builder()
                .timestamp(older).level(LogLevel.INFO).category(LogCategory.SYSTEM).message("old").build());
        repository.save(ActivityLog.builder()
                .timestamp(newer).level(LogLevel.INFO).category(LogCategory.SYSTEM).message("new").build());

        List<ActivityLog> content = repository.findAllByOrderByTimestampDesc(PageRequest.of(0, 50)).getContent();

        // "new" (newer timestamp) should come before "old"
        int newIdx = -1, oldIdx = -1;
        for (int i = 0; i < content.size(); i++) {
            if ("new".equals(content.get(i).getMessage())) newIdx = i;
            if ("old".equals(content.get(i).getMessage())) oldIdx = i;
        }
        assertThat(newIdx).isLessThan(oldIdx);
    }

    // --- findByLevel ---

    @Test
    void findByLevelOrderByTimestampDesc_returnsOnlyMatchingLevel() {
        repository.save(entry(LogLevel.ERROR, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.ERROR, LogCategory.EXECUTION));

        Page<ActivityLog> result = repository.findByLevelOrderByTimestampDesc(LogLevel.ERROR, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.getContent()).allMatch(l -> l.getLevel() == LogLevel.ERROR);
    }

    @Test
    void findByLevelOrderByTimestampDesc_emptyWhenNoMatch() {
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));

        Page<ActivityLog> result = repository.findByLevelOrderByTimestampDesc(LogLevel.DEBUG, PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }

    // --- findByCategory ---

    @Test
    void findByCategoryOrderByTimestampDesc_returnsOnlyMatchingCategory() {
        repository.save(entry(LogLevel.INFO, LogCategory.SCHEDULE));
        repository.save(entry(LogLevel.WARN, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.ERROR, LogCategory.SCHEDULE));

        Page<ActivityLog> result = repository.findByCategoryOrderByTimestampDesc(LogCategory.SCHEDULE, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.getContent()).allMatch(l -> l.getCategory() == LogCategory.SCHEDULE);
    }

    @Test
    void findByCategoryOrderByTimestampDesc_emptyWhenNoMatch() {
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));

        Page<ActivityLog> result = repository.findByCategoryOrderByTimestampDesc(LogCategory.WEBHOOK, PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }

    // --- findByLevelAndCategory ---

    @Test
    void findByLevelAndCategoryOrderByTimestampDesc_filtersBothDimensions() {
        repository.save(entry(LogLevel.ERROR, LogCategory.SECURITY));
        repository.save(entry(LogLevel.ERROR, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.WARN, LogCategory.SECURITY));

        Page<ActivityLog> result = repository.findByLevelAndCategoryOrderByTimestampDesc(
                LogLevel.ERROR, LogCategory.SECURITY, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(result.getContent().get(0).getCategory()).isEqualTo(LogCategory.SECURITY);
    }

    @Test
    void findByLevelAndCategoryOrderByTimestampDesc_emptyWhenNoExactMatch() {
        repository.save(entry(LogLevel.ERROR, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.WARN, LogCategory.SECURITY));

        Page<ActivityLog> result = repository.findByLevelAndCategoryOrderByTimestampDesc(
                LogLevel.ERROR, LogCategory.SECURITY, PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }

    // --- findByWorkflowId ---

    @Test
    void findByWorkflowIdOrderByTimestampDesc_filtersToWorkflow() {
        String wfId = "wf-abc";
        repository.save(entryWithWorkflow(LogLevel.INFO, LogCategory.EXECUTION, wfId));
        repository.save(entryWithWorkflow(LogLevel.ERROR, LogCategory.EXECUTION, wfId));
        repository.save(entryWithWorkflow(LogLevel.INFO, LogCategory.EXECUTION, "other-wf"));

        Page<ActivityLog> result = repository.findByWorkflowIdOrderByTimestampDesc(wfId, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).allMatch(l -> wfId.equals(l.getWorkflowId()));
    }

    @Test
    void findByWorkflowIdOrderByTimestampDesc_emptyForUnknownWorkflow() {
        repository.save(entryWithWorkflow(LogLevel.INFO, LogCategory.EXECUTION, "wf-x"));

        Page<ActivityLog> result = repository.findByWorkflowIdOrderByTimestampDesc("no-such-wf", PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }

    // --- findByWorkflowIdAndLevel ---

    @Test
    void findByWorkflowIdAndLevelOrderByTimestampDesc_filtersBothDimensions() {
        String wfId = "wf-xyz";
        repository.save(entryWithWorkflow(LogLevel.ERROR, LogCategory.EXECUTION, wfId));
        repository.save(entryWithWorkflow(LogLevel.INFO, LogCategory.EXECUTION, wfId));
        repository.save(entryWithWorkflow(LogLevel.ERROR, LogCategory.EXECUTION, "other"));

        Page<ActivityLog> result = repository.findByWorkflowIdAndLevelOrderByTimestampDesc(
                wfId, LogLevel.ERROR, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getWorkflowId()).isEqualTo(wfId);
        assertThat(result.getContent().get(0).getLevel()).isEqualTo(LogLevel.ERROR);
    }

    // --- findByWorkflowIdAndCategory ---

    @Test
    void findByWorkflowIdAndCategoryOrderByTimestampDesc_filtersBothDimensions() {
        String wfId = "wf-cat";
        repository.save(entryWithWorkflow(LogLevel.INFO, LogCategory.CONTAINER, wfId));
        repository.save(entryWithWorkflow(LogLevel.WARN, LogCategory.EXECUTION, wfId));

        Page<ActivityLog> result = repository.findByWorkflowIdAndCategoryOrderByTimestampDesc(
                wfId, LogCategory.CONTAINER, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCategory()).isEqualTo(LogCategory.CONTAINER);
    }

    // --- findByWorkflowIdAndLevelAndCategory ---

    @Test
    void findByWorkflowIdAndLevelAndCategoryOrderByTimestampDesc_allThreeFilters() {
        String wfId = "wf-all";
        repository.save(entryWithWorkflow(LogLevel.ERROR, LogCategory.EXECUTION, wfId));
        repository.save(entryWithWorkflow(LogLevel.ERROR, LogCategory.SYSTEM, wfId));   // wrong category
        repository.save(entryWithWorkflow(LogLevel.WARN, LogCategory.EXECUTION, wfId)); // wrong level
        repository.save(entryWithWorkflow(LogLevel.ERROR, LogCategory.EXECUTION, "other-wf")); // wrong wf

        Page<ActivityLog> result = repository.findByWorkflowIdAndLevelAndCategoryOrderByTimestampDesc(
                wfId, LogLevel.ERROR, LogCategory.EXECUTION, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getWorkflowId()).isEqualTo(wfId);
        assertThat(result.getContent().get(0).getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(result.getContent().get(0).getCategory()).isEqualTo(LogCategory.EXECUTION);
    }

    // --- Pageable ---

    @Test
    void pageable_limitsAndPaginatesResults() {
        for (int i = 0; i < 5; i++) {
            repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        }

        Page<ActivityLog> page0 = repository.findByLevelOrderByTimestampDesc(LogLevel.INFO, PageRequest.of(0, 3));
        Page<ActivityLog> page1 = repository.findByLevelOrderByTimestampDesc(LogLevel.INFO, PageRequest.of(1, 3));

        assertThat(page0.getContent()).hasSize(3);
        assertThat(page0.getTotalElements()).isGreaterThanOrEqualTo(5);
        assertThat(page1.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    // --- deleteOlderThan ---

    @Test
    void deleteOlderThan_deletesAllEntriesOlderThanCutoff() {
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.ERROR, LogCategory.EXECUTION));
        repository.flush(); // ensure createdAt is populated by auditing listener

        // Future cutoff: all just-saved entries (createdAt ≈ now) are "older than" future
        int deleted = repository.deleteOlderThan(Instant.now().plusSeconds(60));

        assertThat(deleted).isGreaterThanOrEqualTo(2);
    }

    @Test
    void deleteOlderThan_doesNotDeleteEntriesNewerThanCutoff() {
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        repository.flush();

        // Past cutoff: nothing was created before an hour ago in this test
        int deleted = repository.deleteOlderThan(Instant.now().minusSeconds(3600));

        assertThat(deleted).isEqualTo(0);
    }

    // --- deleteByLevelsOlderThan ---

    @Test
    void deleteByLevelsOlderThan_deletesOnlyMatchingLevel() {
        repository.save(entry(LogLevel.DEBUG, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.ERROR, LogCategory.SYSTEM));
        repository.flush();

        int deleted = repository.deleteByLevelsOlderThan(List.of(LogLevel.DEBUG), Instant.now().plusSeconds(60));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        // INFO and ERROR should still be present
        assertThat(repository.findByLevelOrderByTimestampDesc(LogLevel.INFO, PageRequest.of(0, 50)).getContent()).isNotEmpty();
        assertThat(repository.findByLevelOrderByTimestampDesc(LogLevel.ERROR, PageRequest.of(0, 50)).getContent()).isNotEmpty();
    }

    @Test
    void deleteByLevelsOlderThan_deletesMultipleLevels() {
        repository.save(entry(LogLevel.DEBUG, LogCategory.SYSTEM));
        repository.save(entry(LogLevel.WARN, LogCategory.EXECUTION));
        repository.save(entry(LogLevel.ERROR, LogCategory.SYSTEM));
        repository.flush();

        int deleted = repository.deleteByLevelsOlderThan(
                List.of(LogLevel.DEBUG, LogLevel.WARN), Instant.now().plusSeconds(60));

        assertThat(deleted).isGreaterThanOrEqualTo(2);
    }

    @Test
    void deleteByLevelsOlderThan_noDeleteWhenLevelNotInList() {
        repository.save(entry(LogLevel.INFO, LogCategory.SYSTEM));
        repository.flush();

        // INFO entry should NOT be deleted since we only target DEBUG
        int deleted = repository.deleteByLevelsOlderThan(List.of(LogLevel.DEBUG), Instant.now().plusSeconds(60));

        assertThat(deleted).isEqualTo(0);
    }

    @Test
    void deleteByLevelsOlderThan_doesNotDeleteEntriesNewerThanCutoff() {
        repository.save(entry(LogLevel.DEBUG, LogCategory.SYSTEM));
        repository.flush();

        // Past cutoff: DEBUG entry was just created, not "older than" an hour ago
        int deleted = repository.deleteByLevelsOlderThan(List.of(LogLevel.DEBUG), Instant.now().minusSeconds(3600));

        assertThat(deleted).isEqualTo(0);
    }
}

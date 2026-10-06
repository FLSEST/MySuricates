package com.automation.repository;

import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ChainRunRepositoryTest {

    @Autowired
    private ChainRunRepository repository;

    @Autowired
    private WorkflowChainRepository chainRepository;

    private WorkflowChain saveChain(String name) {
        return chainRepository.save(WorkflowChain.builder()
                .name(name)
                .build());
    }

    private ChainRun run(WorkflowChain chain, RunStatus status) {
        return ChainRun.builder()
                .chain(chain)
                .status(status)
                .startedAt(Instant.now())
                .build();
    }

    // --- countByChainId ---

    @Test
    void countByChainId_returnsCorrectCount() {
        WorkflowChain chain = saveChain("My Chain");
        WorkflowChain other = saveChain("Other Chain");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));
        repository.save(run(other, RunStatus.COMPLETED));

        long count = repository.countByChainId(chain.getId());

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countByChainId_returnsZeroForUnknownChain() {
        long count = repository.countByChainId("no-such-chain");

        assertThat(count).isZero();
    }

    @Test
    void countRunsGroupedByChain_matchesPerChainCounts() {
        WorkflowChain chain = saveChain("Grouped Chain");
        WorkflowChain other = saveChain("Grouped Other");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));
        repository.save(run(other, RunStatus.COMPLETED));

        Map<String, Long> grouped = repository.countRunsGroupedByChain().stream()
                .collect(java.util.stream.Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));

        assertThat(grouped.get(chain.getId())).isEqualTo(2L);
        assertThat(grouped.get(other.getId())).isEqualTo(1L);
        assertThat(grouped.get(chain.getId())).isEqualTo(repository.countByChainId(chain.getId()));
    }

    // --- countByStatus ---

    @Test
    void countByStatus_returnsCorrectCount() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.RUNNING));
        repository.save(run(chain, RunStatus.RUNNING));
        repository.save(run(chain, RunStatus.COMPLETED));

        long count = repository.countByStatus(RunStatus.RUNNING);

        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void countByStatus_returnsZeroWhenNoMatch() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.COMPLETED));

        long count = repository.countByStatus(RunStatus.CANCELLED);

        assertThat(count).isZero();
    }

    // --- findByChainIdOrderByStartedAtDesc ---

    @Test
    void findByChainIdOrderByStartedAtDesc_returnsOnlyRunsForChain() {
        WorkflowChain chain = saveChain("Chain A");
        WorkflowChain other = saveChain("Chain B");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));
        repository.save(run(other, RunStatus.COMPLETED));

        List<ChainRun> result = repository.findByChainIdOrderByStartedAtDesc(chain.getId());

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(r -> r.getChain().getId().equals(chain.getId()));
    }

    @Test
    void findByChainIdOrderByStartedAtDesc_orderedNewestFirst() {
        WorkflowChain chain = saveChain("Chain");
        Instant older = Instant.now().minusSeconds(10);
        Instant newer = Instant.now();

        repository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED).startedAt(older).build());
        repository.save(ChainRun.builder().chain(chain).status(RunStatus.FAILED).startedAt(newer).build());

        List<ChainRun> result = repository.findByChainIdOrderByStartedAtDesc(chain.getId());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartedAt()).isAfterOrEqualTo(result.get(1).getStartedAt());
    }

    /** The chain runs of a schedule: the newest up to the limit, plus their total count (TODO 86777fb4). */
    @Test
    void findByTriggeredByScheduleId_withLimit_returnsTheNewest_andTheCountAll() {
        WorkflowChain chain = saveChain("Scheduled Chain");
        String scheduleId = "schedule-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        repository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED)
                .triggeredByScheduleId(scheduleId).startedAt(now.minusSeconds(20)).build());
        ChainRun newest = repository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED)
                .triggeredByScheduleId(scheduleId).startedAt(now).build());
        repository.save(run(chain, RunStatus.COMPLETED));

        assertThat(repository.findByTriggeredByScheduleIdOrderByStartedAtDesc(scheduleId, Limit.of(1)))
                .extracting(ChainRun::getId).containsExactly(newest.getId());
        assertThat(repository.countByTriggeredByScheduleId(scheduleId)).isEqualTo(2);
    }

    @Test
    void findByChainIdOrderByStartedAtDesc_emptyForUnknownChain() {
        List<ChainRun> result = repository.findByChainIdOrderByStartedAtDesc("no-such-chain");

        assertThat(result).isEmpty();
    }

    // --- findByOrderByStartedAtDesc ---

    @Test
    void findByOrderByStartedAtDesc_returnsAllRuns() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));

        Page<ChainRun> page = repository.findByOrderByStartedAtDesc(PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    // --- findByStatusOrderByStartedAtDesc ---

    @Test
    void findByStatusOrderByStartedAtDesc_returnsOnlyMatchingStatus() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));

        Page<ChainRun> result = repository.findByStatusOrderByStartedAtDesc(RunStatus.COMPLETED, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.getContent()).allMatch(r -> r.getStatus() == RunStatus.COMPLETED);
    }

    @Test
    void findByStatusOrderByStartedAtDesc_emptyWhenNoMatch() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.COMPLETED));

        Page<ChainRun> result = repository.findByStatusOrderByStartedAtDesc(RunStatus.CANCELLED, PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }

    // --- existsByChainIdAndStatus ---

    @Test
    void existsByChainIdAndStatus_returnsTrueWhenExists() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.RUNNING));

        boolean exists = repository.existsByChainIdAndStatus(chain.getId(), RunStatus.RUNNING);

        assertThat(exists).isTrue();
    }

    @Test
    void existsByChainIdAndStatus_returnsFalseWhenStatusMismatch() {
        WorkflowChain chain = saveChain("Chain");
        repository.save(run(chain, RunStatus.COMPLETED));

        boolean exists = repository.existsByChainIdAndStatus(chain.getId(), RunStatus.RUNNING);

        assertThat(exists).isFalse();
    }

    @Test
    void existsByChainIdAndStatus_returnsFalseForUnknownChain() {
        boolean exists = repository.existsByChainIdAndStatus("no-chain", RunStatus.RUNNING);

        assertThat(exists).isFalse();
    }

    /** The scheduler's SKIP check: a PAUSED run still occupies the chain (TODO ade3fef8). */
    @Test
    void existsByChainIdAndStatusIn_matchesAnyOfTheStatuses() {
        WorkflowChain chain = saveChain("Paused Chain");
        WorkflowChain other = saveChain("Finished Chain");
        repository.save(run(chain, RunStatus.PAUSED));
        repository.save(run(other, RunStatus.FAILED));
        var busy = java.util.EnumSet.of(RunStatus.RUNNING, RunStatus.PAUSED);

        assertThat(repository.existsByChainIdAndStatusIn(chain.getId(), busy)).isTrue();
        assertThat(repository.existsByChainIdAndStatusIn(other.getId(), busy)).isFalse();
    }

    // --- findStatusesByScheduleIdNewestFirst ---

    /** Only the status column, newest first, only the schedule's own (TODO 86286572). */
    @Test
    void findStatusesByScheduleIdNewestFirst_returnsTheNewestStatusOfTheSchedule() {
        WorkflowChain chain = saveChain("Chain");
        String scheduleId = "sched-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        repository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED)
                .startedAt(now.minusSeconds(60)).triggeredByScheduleId(scheduleId).build());
        repository.save(ChainRun.builder().chain(chain).status(RunStatus.FAILED)
                .startedAt(now).triggeredByScheduleId(scheduleId).build());
        repository.save(ChainRun.builder().chain(chain).status(RunStatus.RUNNING)
                .startedAt(now.plusSeconds(60)).triggeredByScheduleId("other-sched").build());

        assertThat(repository.findStatusesByScheduleIdNewestFirst(scheduleId, PageRequest.of(0, 10)))
                .containsExactly(RunStatus.FAILED, RunStatus.COMPLETED);
        assertThat(repository.findStatusesByScheduleIdNewestFirst(scheduleId, PageRequest.of(0, 1)))
                .containsExactly(RunStatus.FAILED);
    }

    @Test
    void findStatusesByScheduleIdNewestFirst_emptyForUnknownSchedule() {
        assertThat(repository.findStatusesByScheduleIdNewestFirst("no-such-sched", PageRequest.of(0, 1))).isEmpty();
    }

    // --- findFiltered ---

    @Test
    void findFiltered_nullStatusAndNullSearch_returnsAll() {
        WorkflowChain chain = saveChain("Alpha Chain");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));

        Page<ChainRun> result = repository.findFiltered(null, null, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void findFiltered_filtersByStatus() {
        WorkflowChain chain = saveChain("Beta Chain");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.RUNNING));

        Page<ChainRun> result = repository.findFiltered(RunStatus.COMPLETED, null, PageRequest.of(0, 50));

        assertThat(result.getContent()).isNotEmpty();
        assertThat(result.getContent()).allMatch(r -> r.getStatus() == RunStatus.COMPLETED);
    }

    @Test
    void findFiltered_filtersByChainNameSearch() {
        WorkflowChain matchChain = saveChain("Gamma Pipeline");
        WorkflowChain otherChain = saveChain("Delta Flow");
        repository.save(run(matchChain, RunStatus.COMPLETED));
        repository.save(run(otherChain, RunStatus.COMPLETED));

        Page<ChainRun> result = repository.findFiltered(null, "Gamma", PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getChain().getName()).isEqualTo("Gamma Pipeline");
    }

    @Test
    void findFiltered_searchIsCaseInsensitive() {
        WorkflowChain chain = saveChain("Epsilon Sequence");
        repository.save(run(chain, RunStatus.COMPLETED));

        Page<ChainRun> result = repository.findFiltered(null, "epsilon", PageRequest.of(0, 50));

        assertThat(result.getContent()).isNotEmpty();
        assertThat(result.getContent().get(0).getChain().getName()).isEqualTo("Epsilon Sequence");
    }

    @Test
    void findFiltered_filtersByStatusAndSearch() {
        WorkflowChain chain = saveChain("Zeta Chain");
        repository.save(run(chain, RunStatus.COMPLETED));
        repository.save(run(chain, RunStatus.FAILED));

        Page<ChainRun> result = repository.findFiltered(RunStatus.COMPLETED, "Zeta", PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    /**
     * The JOIN FETCH of the run list needs its own count query (TODO 44db05fd). It only kicks in
     * when the page is full – and must then apply the same filters as the list.
     */
    @Test
    void findFiltered_fullPage_countsWithTheSameFilters() {
        WorkflowChain theta = saveChain("Theta Chain");
        WorkflowChain other = saveChain("Iota Chain");
        for (int i = 0; i < 3; i++) {
            repository.save(run(theta, RunStatus.COMPLETED));
        }
        repository.save(run(theta, RunStatus.FAILED));
        repository.save(run(other, RunStatus.COMPLETED));

        Page<ChainRun> result = repository.findFiltered(RunStatus.COMPLETED, "theta", PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent()).allMatch(r -> r.getChain().getName().equals("Theta Chain"));
    }

    @Test
    void findFiltered_emptyWhenNoChainNameMatch() {
        WorkflowChain chain = saveChain("Eta Chain");
        repository.save(run(chain, RunStatus.COMPLETED));

        Page<ChainRun> result = repository.findFiltered(null, "NoMatch", PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }
}

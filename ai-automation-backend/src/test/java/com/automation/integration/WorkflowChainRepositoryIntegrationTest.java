package com.automation.integration;

import com.automation.entity.ChainEntry;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowChainRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WorkflowChainRepository against real SQLite (in-memory).
 * Validates CRUD operations, JPA converters (ChainEntryListConverter, JsonMapConverter),
 * and entity mappings for WorkflowChain.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowChainRepositoryIntegrationTest {

    @Autowired private WorkflowChainRepository chainRepository;
    @Autowired private ChainRunRepository chainRunRepository;
    @Autowired private EntityManager entityManager;

    /** V96 (TODO 6db47bc2): der Starter der Kette liegt an der Chain-Run-Zeile, nicht nur im Kontext der Einträge. */
    @Test
    void chainRun_triggerSource_roundTrip() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Chain with Starter").entries(new ArrayList<>()).build());
        String withSource = chainRunRepository.save(ChainRun.builder().chain(chain).status(RunStatus.RUNNING)
                .startedAt(Instant.now()).triggerSource(TriggerSource.WISH).build()).getId();
        String withoutSource = chainRunRepository.save(ChainRun.builder().chain(chain).status(RunStatus.RUNNING)
                .startedAt(Instant.now()).build()).getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(chainRunRepository.findById(withSource).orElseThrow().getTriggerSource()).isEqualTo(TriggerSource.WISH);
        assertThat(chainRunRepository.findById(withoutSource).orElseThrow().getTriggerSource()).isNull();
    }

    @Test
    void chainCrud_createAndFind() {
        WorkflowChain chain = WorkflowChain.builder()
                .name("Test Chain")
                .description("A test chain")
                .icon("link")
                .build();
        chain = chainRepository.save(chain);

        assertThat(chain.getId()).isNotNull();
        assertThat(chainRepository.findById(chain.getId())).isPresent();
    }

    @Test
    void chainCrud_findById_returnsAllFields() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Full Chain")
                .description("Chain with all fields")
                .icon("zap")
                .stopOnFailure(false)
                .build());

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Full Chain");
        assertThat(loaded.getDescription()).isEqualTo("Chain with all fields");
        assertThat(loaded.getIcon()).isEqualTo("zap");
        assertThat(loaded.isStopOnFailure()).isFalse();
    }

    @Test
    void chainCrud_update() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Original").build());

        chain.setName("Updated");
        chain.setDescription("Now with description");
        chainRepository.save(chain);

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Updated");
        assertThat(loaded.getDescription()).isEqualTo("Now with description");
    }

    @Test
    void chainCrud_delete() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("ToDelete").build());
        String id = chain.getId();

        chainRepository.deleteById(id);

        assertThat(chainRepository.findById(id)).isEmpty();
    }

    @Test
    void chainCrud_findAll() {
        chainRepository.save(WorkflowChain.builder().name("Chain A").build());
        chainRepository.save(WorkflowChain.builder().name("Chain B").build());

        List<WorkflowChain> all = chainRepository.findAll();
        assertThat(all).extracting(WorkflowChain::getName).contains("Chain A", "Chain B");
    }

    @Test
    void chainEntryListConverter_roundTrip() {
        WorkflowChain chain = WorkflowChain.builder()
                .name("Chain with Entries")
                .entries(new ArrayList<>(List.of(
                        ChainEntry.builder()
                                .workflowId("wf-1")
                                .label("First Step")
                                .sortOrder(0)
                                .stopOnFailure(true)
                                .build(),
                        ChainEntry.builder()
                                .workflowId("wf-2")
                                .label("Second Step")
                                .sortOrder(1)
                                .stopOnFailure(false)
                                .inputContext(Map.of("key", "value"))
                                .build()
                )))
                .build();
        chain = chainRepository.save(chain);

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getEntries()).hasSize(2);
        assertThat(loaded.getEntries().get(0).getWorkflowId()).isEqualTo("wf-1");
        assertThat(loaded.getEntries().get(0).getLabel()).isEqualTo("First Step");
        assertThat(loaded.getEntries().get(0).getSortOrder()).isZero();
        assertThat(loaded.getEntries().get(0).isStopOnFailure()).isTrue();
        assertThat(loaded.getEntries().get(1).getWorkflowId()).isEqualTo("wf-2");
        assertThat(loaded.getEntries().get(1).isStopOnFailure()).isFalse();
        assertThat(loaded.getEntries().get(1).getInputContext()).containsEntry("key", "value");
    }

    @Test
    void chainEntryListConverter_emptyEntries() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Empty Entries Chain")
                .entries(new ArrayList<>())
                .build());

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getEntries()).isEmpty();
    }

    @Test
    void jsonMapConverter_metadataRoundTrip() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Chain with Metadata")
                .metadata(Map.of("source", "test", "version", 2))
                .build());

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getMetadata()).containsEntry("source", "test");
        assertThat(loaded.getMetadata()).containsEntry("version", 2);
    }

    @Test
    void jsonMapConverter_emptyMetadata() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("No Metadata Chain")
                .build());

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getMetadata()).isEmpty();
    }

    @Test
    void stopOnFailure_defaultIsTrue() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Default StopOnFailure").build());

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.isStopOnFailure()).isTrue();
    }

    @Test
    void auditFields_createdAtAndUpdatedAt() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Audit Test Chain").build());

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void cascadeDelete_removesChainRuns() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder()
                .name("Chain with Runs")
                .entries(new ArrayList<>())
                .build());

        ChainRun run = ChainRun.builder()
                .chain(chain)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .durationMs(500L)
                .build();
        chain.getRuns().add(run);
        chainRepository.save(chain);

        WorkflowChain loaded = chainRepository.findById(chain.getId()).orElseThrow();
        assertThat(loaded.getRuns()).hasSize(1);
        assertThat(loaded.getRuns().get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);

        chainRepository.deleteById(chain.getId());
        assertThat(chainRepository.findById(chain.getId())).isEmpty();
    }
}

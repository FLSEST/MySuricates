package com.automation.repository;

import com.automation.entity.WorkflowChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowChainRepositoryTest {

    @Autowired
    private WorkflowChainRepository repository;

    @Test
    void should_persist_and_assign_id_when_saving_chain() {
        WorkflowChain chain = WorkflowChain.builder().name("My Chain").build();

        WorkflowChain saved = repository.save(chain);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getName()).isEqualTo("My Chain");
        assertThat(saved.isStopOnFailure()).isTrue();
        assertThat(saved.getEntries()).isEmpty();
        assertThat(saved.getMetadata()).isEmpty();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void should_return_chain_when_findById_with_existing_id() {
        WorkflowChain saved = repository.save(WorkflowChain.builder().name("Findable").build());

        Optional<WorkflowChain> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Findable");
    }

    @Test
    void should_return_empty_when_findById_with_unknown_id() {
        Optional<WorkflowChain> found = repository.findById("does-not-exist");

        assertThat(found).isEmpty();
    }

    @Test
    void should_remove_chain_when_deleted() {
        WorkflowChain saved = repository.save(WorkflowChain.builder().name("To Delete").build());
        String id = saved.getId();
        assertThat(repository.existsById(id)).isTrue();

        repository.deleteById(id);

        assertThat(repository.existsById(id)).isFalse();
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void should_include_saved_chain_in_findAll() {
        long initialCount = repository.count();
        repository.save(WorkflowChain.builder().name("In List").build());

        assertThat(repository.count()).isEqualTo(initialCount + 1);
        assertThat(repository.findAll())
                .extracting(WorkflowChain::getName)
                .contains("In List");
    }
}

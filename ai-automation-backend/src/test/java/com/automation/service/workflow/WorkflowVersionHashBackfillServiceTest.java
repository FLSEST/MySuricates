package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowVersion;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowVersionHashBackfillServiceTest {

    @Mock private WorkflowVersionRepository versionRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();

    @Test
    void hashesStoredSnapshotsLikeLiveDefinitions() throws Exception {
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                List.of(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of("command", "make"))).build()),
                "instr", "opus", null, Map.of(), Map.of(), List.of("s1"), null);
        WorkflowVersion good = WorkflowVersion.builder().id("v1").workflowId("wf-1").versionNumber(1)
                .snapshot(objectMapper.writeValueAsString(snapshot)).build();
        WorkflowVersion broken = WorkflowVersion.builder().id("v2").workflowId("wf-1").versionNumber(2)
                .snapshot("{not json").build();
        when(versionRepository.findIdsWithoutDefinitionHash()).thenReturn(List.of("v1", "v2"));
        when(versionRepository.findAllById(List.of("v1", "v2"))).thenReturn(List.of(good, broken));
        when(transactionTemplate.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));

        service(ModuleRegistry.allEnabled()).backfill();

        assertThat(good.getDefinitionHash()).isEqualTo(hasher.hash(snapshot));
        assertThat(broken.getDefinitionHash()).isNull();
    }

    @Test
    void doesNothingWithoutProvenance() {
        service(ModuleRegistry.of(Map.of(), Map.of("provenance", "false"))).backfill();

        verifyNoInteractions(versionRepository, transactionTemplate);
    }

    private WorkflowVersionHashBackfillService service(ModuleRegistry modules) {
        return new WorkflowVersionHashBackfillService(versionRepository, hasher, objectMapper, transactionTemplate, modules);
    }
}

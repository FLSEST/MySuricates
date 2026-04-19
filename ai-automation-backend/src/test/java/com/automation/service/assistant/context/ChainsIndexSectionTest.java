package com.automation.service.assistant.context;

import com.automation.entity.ChainEntry;
import com.automation.entity.WorkflowChain;
import com.automation.repository.WorkflowChainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainsIndexSectionTest {

    @Mock private WorkflowChainRepository chainRepository;

    private ChainsIndexSection section;

    @BeforeEach
    void setUp() {
        section = new ChainsIndexSection(chainRepository);
    }

    private WorkflowChain chain(String id, String name, String desc, int entryCount) {
        List<ChainEntry> entries = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            entries.add(ChainEntry.builder().workflowId("wf-" + i).build());
        }
        return WorkflowChain.builder()
                .id(id)
                .name(name)
                .description(desc)
                .entries(entries)
                .build();
    }

    @Test
    void compact_showsCount() {
        when(chainRepository.findAll()).thenReturn(List.of(
                chain("c1", "Daily Pipeline", "desc", 4),
                chain("c2", "Weekly Pipeline", "desc", 2)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).contains("**Chains:** 2 verfügbar");
    }

    @Test
    void compact_noChains_appendsNothing() {
        when(chainRepository.findAll()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsChainDetails() {
        when(chainRepository.findAll()).thenReturn(List.of(
                chain("c1", "Daily Report", "Tagesreport mit 4 Stufen", 4)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("**Chains (1):**")
                .contains("Daily Report")
                .contains("Tagesreport mit 4 Stufen")
                .contains("4 Workflows, id: c1");
    }

    @Test
    void full_truncatesAtMaxEntriesWithHint() {
        List<WorkflowChain> many = IntStream.range(0, 20)
                .mapToObj(i -> chain("id-" + i, "Chain " + i, "d", 1))
                .toList();
        when(chainRepository.findAll()).thenReturn(many);

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("**Chains (20):**")
                .contains("… und 5 weitere")
                .contains("<fetch type=\"chain\"");
    }

    @Test
    void full_handlesNullEntries() {
        WorkflowChain c = WorkflowChain.builder().id("c1").name("Empty").entries(null).build();
        when(chainRepository.findAll()).thenReturn(List.of(c));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).contains("0 Workflows, id: c1");
    }

    @Test
    void exception_doesNotThrow() {
        when(chainRepository.findAll()).thenThrow(new RuntimeException("boom"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }
}

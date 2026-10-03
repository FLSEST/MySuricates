package com.automation.integration;

import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowChainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Run-Detailantwort nennt die Kette ihres Ketten-Runs (3a35ced1), damit die Run-Herkunft auf
 * {@code /chains?chain=<id>} verlinkt. Gegen echtes SQLite, weil die JPQL nur die Fremdschlüsselspalte liest.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ChainRunChainIdLookupIntegrationTest {

    @Autowired private WorkflowChainRepository chainRepository;
    @Autowired private ChainRunRepository chainRunRepository;

    @Test
    void findsTheChainOfAChainRun_andNothingForAGoneOne() {
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder().name("Wochen-Audits").build());
        ChainRun chainRun = chainRunRepository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED)
                .startedAt(Instant.parse("2026-10-03T06:00:00Z")).build());

        assertThat(chainRunRepository.findChainIdById(chainRun.getId())).contains(chain.getId());
        assertThat(chainRunRepository.findChainIdById("chain-run-gone")).isEmpty();
    }
}

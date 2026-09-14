package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.chain.ChainService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static com.automation.config.ExecutionDefaults.POLLING_INTERVAL_MS;

@Service
@Slf4j
public class WishExecutionService {

    private final WishConfig wishConfig;
    private final WishRepository wishRepository;
    private final ChainService chainService;
    private final ChainExecutor chainExecutor;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowService workflowService;
    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public WishExecutionService(
            WishConfig wishConfig,
            WishRepository wishRepository,
            ChainService chainService,
            ChainExecutor chainExecutor,
            @Lazy WorkflowExecutor workflowExecutor,
            WorkflowService workflowService,
            WorkflowRunRepository workflowRunRepository,
            ChainRunRepository chainRunRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.wishConfig = wishConfig;
        this.wishRepository = wishRepository;
        this.chainService = chainService;
        this.chainExecutor = chainExecutor;
        this.workflowExecutor = workflowExecutor;
        this.workflowService = workflowService;
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public void execute(Wish wish, Supplier<Boolean> isCancelled) throws Exception {
        log.info("Wish {}: Executing iteration {}", wish.getId(), wish.getIterationCount());

        if (wish.getGeneratedChainId() != null) {
            WorkflowChain chain = chainService.findById(wish.getGeneratedChainId());
            ChainRun chainRun = chainExecutor.execute(chain, Map.of(), null, TriggerSource.WISH);
            wish.setCurrentRunId(chainRun.getId());
            WishStatusHelper.updateCurrentIterationEntry(wish, "runId", chainRun.getId());
            wishRepository.save(wish);
            WishStatusHelper.broadcastStatus(messagingTemplate, wish.getId(), wish);

            waitForChainCompletion(chainRun.getId(), wish.getId(), isCancelled);
        } else if (!wish.getGeneratedWorkflowIds().isEmpty()) {
            String wfId = wish.getGeneratedWorkflowIds().get(0);
            Workflow workflow = workflowService.findByIdEager(wfId);
            WorkflowRun workflowRun = workflowExecutor.execute(workflow, Map.of(), TriggerSource.WISH);
            wish.setCurrentRunId(workflowRun.getId());
            WishStatusHelper.updateCurrentIterationEntry(wish, "runId", workflowRun.getId());
            wishRepository.save(wish);
            WishStatusHelper.broadcastStatus(messagingTemplate, wish.getId(), wish);

            waitForWorkflowCompletion(workflowRun.getId(), wish.getId(), isCancelled);
        } else {
            wish.setStatus(WishStatus.FAILED);
            wish.setError("No workflows were generated during decomposition");
            wishRepository.save(wish);
            WishStatusHelper.broadcastStatus(messagingTemplate, wish.getId(), wish);
            return;
        }

        // Move to evaluation phase
        Wish updated = wishRepository.findById(wish.getId()).orElseThrow();
        WishStatusHelper.updateStatus(wishRepository, messagingTemplate, updated, WishStatus.EVALUATING);
    }

    private void waitForWorkflowCompletion(String runId, String wishId, Supplier<Boolean> isCancelled) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(wishConfig.getWorkflowWaitMinutes()));
        while (Instant.now().isBefore(deadline)) {
            if (isCancelled.get()) return;
            WorkflowRun run = workflowRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return;
            }
            sleep();
        }
        log.warn("Wish {}: Workflow {} timed out", wishId, runId);
    }

    private void waitForChainCompletion(String runId, String wishId, Supplier<Boolean> isCancelled) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(wishConfig.getWorkflowWaitMinutes()));
        while (Instant.now().isBefore(deadline)) {
            if (isCancelled.get()) return;
            ChainRun run = chainRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return;
            }
            sleep();
        }
        log.warn("Wish {}: Chain {} timed out", wishId, runId);
    }

    private void sleep() {
        try {
            Thread.sleep(POLLING_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

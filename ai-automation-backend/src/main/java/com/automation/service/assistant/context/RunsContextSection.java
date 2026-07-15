package com.automation.service.assistant.context;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.assistant.AssistantFormatUtils;
import com.automation.service.system.SystemIdentityService;
import com.automation.util.TruncationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class RunsContextSection implements AssistantContextSection {

    private final WorkflowRunRepository workflowRunRepository;
    private final SystemIdentityService systemIdentity;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        sb.append("## My Current State\n");
        sb.append("Version: ").append(systemIdentity.getVersion()).append("\n\n");

        if (compact) {
            try {
                long running = workflowRunRepository.countByStatus(RunStatus.RUNNING);
                List<WorkflowRun> recent = workflowRunRepository.findTop10ByOrderByStartedAtDesc();
                List<WorkflowRun> failed = recent.stream()
                        .filter(r -> r.getStatus() == RunStatus.FAILED).toList();
                String failedIds = failed.stream()
                        .map(r -> r.getId().substring(0, Math.min(8, r.getId().length())))
                        .collect(Collectors.joining(", "));
                sb.append("**Runs:** ").append(recent.size()).append(" recent, ")
                        .append(running).append(" running, ").append(failed.size()).append(" FAILED");
                if (!failedIds.isEmpty()) sb.append(" (ids: ").append(failedIds).append(")");
                sb.append("\n\n");
            } catch (Exception e) {
                log.debug("Could not load runs for compact context", e);
            }
            return;
        }

        try {
            List<WorkflowRun> active = workflowRunRepository.findByStatus(RunStatus.RUNNING);
            sb.append("**Active Runs:** ").append(active.size()).append("\n");
            active.forEach(r -> sb.append("  - ").append(runLabel(r))
                    .append(" (link:").append(runLink(r)).append(")\n"));
        } catch (Exception e) {
            log.debug("Could not load active runs for assistant context", e);
        }

        sb.append("\n**Last 10 Runs:**\n");
        try {
            workflowRunRepository.findTop10ByOrderByStartedAtDesc().forEach(r -> {
                sb.append("  - [").append(fmt(r.getStartedAt())).append("] ")
                        .append(runLabel(r)).append(" — ").append(r.getStatus())
                        .append(" (link:").append(runLink(r)).append(")");
                if (r.getStatus() == RunStatus.FAILED) {
                    String err = extractError(r);
                    if (err != null) sb.append(" — Error: ").append(TruncationUtil.tailInline(err, 120));
                }
                sb.append("\n");
            });
        } catch (Exception e) {
            log.debug("Could not load recent runs for assistant context", e);
        }
        sb.append("\n");
    }

    // ── Shared helpers (implementation centralized in AssistantFormatUtils) ──

    public String runLink(WorkflowRun run) {
        return AssistantFormatUtils.runLink(run);
    }

    public String runLabel(WorkflowRun run) {
        return AssistantFormatUtils.runLabel(run);
    }

    public String fmt(Instant instant) {
        return AssistantFormatUtils.fmt(instant);
    }

    public String extractError(WorkflowRun run) {
        return AssistantFormatUtils.extractError(run);
    }

    public String truncate(String s, int max) {
        return AssistantFormatUtils.truncate(s, max);
    }
}

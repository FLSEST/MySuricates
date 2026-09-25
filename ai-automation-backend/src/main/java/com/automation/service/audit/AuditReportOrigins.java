package com.automation.service.audit;

import com.automation.dto.projection.ChainRunOriginRow;
import com.automation.dto.projection.RunOriginRow;
import com.automation.entity.TriggerSource;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.repository.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where the run behind an audit report came from: trigger, schedule, chain and the definition
 * version it executed (TODO a3b89355). Resolved for a whole page of reports at once — one query for
 * the runs, one for their chain runs with the chain, one for the version numbers — never per report.
 */
@Component
@RequiredArgsConstructor
class AuditReportOrigins {

    private final WorkflowRunRepository runRepository;
    private final ChainRunRepository chainRunRepository;
    private final WorkflowVersionRepository versionRepository;

    /**
     * Each field is {@code null} when unknown — a run from before the column existed, a deleted run or
     * chain, provenance switched off. Nothing is derived beyond what the run recorded.
     *
     * @param triggerSource effective trigger: for a chain entry the source that started the chain
     *                      ({@link TriggerSource#effective}; a resumed entry says {@code RESUME});
     *                      otherwise the run's own. A chain run from before V89 has no chain run to
     *                      show and stays {@code CHAIN}; a sub-workflow run stays {@code SUB_WORKFLOW} —
     *                      without a link to its parent run, the parent's trigger would read as its own.
     * @param scheduleId    schedule that started the run, or for a chain entry the one that started the chain
     */
    record Origin(TriggerSource triggerSource, String scheduleId, String chainRunId, String chainId,
                  String chainName, Integer definitionVersionNumber) {
        static final Origin UNKNOWN = new Origin(null, null, null, null, null, null);
    }

    /** Origin per run id; a run that no longer exists is missing from the map. */
    Map<String, Origin> byRunId(Collection<String> runIds) {
        List<String> ids = runIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<RunOriginRow> runs = runRepository.findOriginRows(ids);
        Set<String> chainRunIds = idsOf(runs, RunOriginRow::chainRunId);
        Map<String, ChainRunOriginRow> chainRuns = chainRunIds.isEmpty() ? Map.of()
                : chainRunRepository.findOriginRows(chainRunIds).stream()
                        .collect(Collectors.toMap(ChainRunOriginRow::id, Function.identity()));
        Set<String> versionIds = idsOf(runs, RunOriginRow::definitionVersionId);
        Map<String, Integer> versionNumbers = versionIds.isEmpty() ? Map.of()
                : versionRepository.findVersionNumbers(versionIds).stream()
                        .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).intValue()));
        return runs.stream().collect(Collectors.toMap(RunOriginRow::id, run -> originOf(run,
                lookup(chainRuns, run.chainRunId()), lookup(versionNumbers, run.definitionVersionId()))));
    }

    /** {@code Map.of()} rejects a null key, so a run without chain run or version must not ask. */
    private static <T> T lookup(Map<String, T> byId, String id) {
        return id != null ? byId.get(id) : null;
    }

    private static Origin originOf(RunOriginRow run, ChainRunOriginRow chainRun, Integer versionNumber) {
        if (run.chainRunId() == null) {
            return new Origin(run.triggerSource(), run.triggeredByScheduleId(), null, null, null, versionNumber);
        }
        Map<String, Object> parent = run.parentTriggerSource() != null
                ? Map.of(TriggerSource.PARENT_CONTEXT_KEY, run.parentTriggerSource()) : Map.of();
        TriggerSource starter = run.triggerSource() != null ? run.triggerSource().effective(parent) : null;
        return new Origin(starter != null ? starter : run.triggerSource(),
                chainRun != null ? chainRun.triggeredByScheduleId() : null, run.chainRunId(),
                chainRun != null ? chainRun.chainId() : null, chainRun != null ? chainRun.chainName() : null,
                versionNumber);
    }

    private static Set<String> idsOf(List<RunOriginRow> runs, Function<RunOriginRow, String> id) {
        return runs.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}

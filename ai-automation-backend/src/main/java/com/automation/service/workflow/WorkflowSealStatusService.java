package com.automation.service.workflow;

import com.automation.dto.response.WorkflowSealStatusResponse;
import com.automation.dto.response.WorkflowSealStatusResponse.SkillRef;
import com.automation.entity.ActivityLog;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.ActivityLogRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the UI shows about seals: the state of every sealed workflow (checked by
 * {@link WorkflowSealVerifier}, the same check RunAdmission runs), the starts refused in the last
 * seven days — which leave no run row and would otherwise only be visible in the activity log — and
 * which sealed workflows protect which skill. Read-only; nothing here changes a seal.
 */
@Service
@RequiredArgsConstructor
public class WorkflowSealStatusService {

    static final Duration REFUSAL_WINDOW = Duration.ofDays(7);
    private static final String ADMISSION_SOURCE = "RunAdmission";

    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final ActivityLogRepository activityLogRepository;
    private final WorkflowSealVerifier verifier;

    @Transactional(readOnly = true)
    public List<WorkflowSealStatusResponse> statusAll() {
        Map<String, List<ActivityLog>> refusals = activityLogRepository
                .findBySourceAndTimestampAfterOrderByTimestampDesc(ADMISSION_SOURCE, Instant.now().minus(REFUSAL_WINDOW))
                .stream()
                .filter(entry -> entry.getWorkflowId() != null)
                .collect(Collectors.groupingBy(ActivityLog::getWorkflowId, LinkedHashMap::new, Collectors.toList()));

        List<WorkflowSealStatusResponse> result = new ArrayList<>();
        for (Workflow workflow : workflowRepository.findSealedWithSkills()) {
            result.add(sealedStatus(workflow, refusals.getOrDefault(workflow.getId(), List.of())));
        }
        var listed = result.stream().map(WorkflowSealStatusResponse::workflowId).collect(Collectors.toSet());
        List<String> unsealedWithRefusals = refusals.keySet().stream().filter(id -> !listed.contains(id)).toList();
        if (!unsealedWithRefusals.isEmpty()) {
            Map<String, String> names = workflowRepository.findIdAndNameByIdIn(unsealedWithRefusals).stream()
                    .collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
            for (String id : unsealedWithRefusals) {
                if (names.containsKey(id)) { // deleted workflows drop out
                    result.add(withRefusals(id, names.get(id), "NOT_SEALED", "NONE", null, null, null, List.of(),
                            false, false, 0, null, refusals.get(id)));
                }
            }
        }
        return result;
    }

    /** skill id → names of the sealed workflows that use it (pinned, activated or referenced from a block). */
    @Transactional(readOnly = true)
    public Map<String, List<String>> sealedWorkflowsBySkill() {
        Map<String, TreeSet<String>> bySkill = new TreeMap<>();
        for (Workflow workflow : workflowRepository.findSealedWithSkills()) {
            for (String skillId : usedSkillIds(workflow)) {
                bySkill.computeIfAbsent(skillId, id -> new TreeSet<>()).add(workflow.getName());
            }
        }
        Map<String, List<String>> result = new TreeMap<>();
        bySkill.forEach((id, names) -> result.put(id, List.copyOf(names)));
        return result;
    }

    private WorkflowSealStatusResponse sealedStatus(Workflow workflow, List<ActivityLog> refusals) {
        WorkflowSealVerifier.Verdict verdict = verifier.verify(workflow);
        List<SkillRef> changed = verdict.changedSkills().isEmpty() ? List.of() : skillRefs(verdict.changedSkills());
        int pinned = 0;
        if (verdict.skillsPinned()) {
            try {
                pinned = WorkflowSkillPins.fromJson(workflow.getSealedSkillHashes()).size();
            } catch (RuntimeException e) {
                pinned = 0; // unreadable pins already make the verdict UNVERIFIABLE
            }
        }
        Map<String, String> refusalParams = null;
        if (verdict.refusal() != null) {
            refusalParams = new LinkedHashMap<>(verdict.refusalParams());
            refusalParams.put("name", String.valueOf(workflow.getName()));
        }
        return withRefusals(workflow.getId(), workflow.getName(), verdict.intact() ? "INTACT" : "VIOLATED",
                verdict.problem().name(), verdict.detail(), refusalParams == null ? null : verdict.refusal().name(),
                refusalParams == null ? null : Map.copyOf(refusalParams), changed, verdict.skillsPinned(),
                verdict.signed(), pinned, workflow.getSealedAt(), refusals);
    }

    private static WorkflowSealStatusResponse withRefusals(String id, String name, String state, String problem,
                                                           String detail, String refusalKey,
                                                           Map<String, String> refusalParams, List<SkillRef> changed,
                                                           boolean skillsPinned, boolean signed, int pinned,
                                                           Instant sealedAt, List<ActivityLog> refusals) {
        ActivityLog last = refusals.isEmpty() ? null : refusals.get(0);
        Map<String, Object> meta = last != null && last.getMetadata() != null ? last.getMetadata() : Map.of();
        return new WorkflowSealStatusResponse(id, name, state, problem, detail, changed, skillsPinned, signed, pinned,
                sealedAt, refusals.size(), last != null ? last.getTimestamp() : null,
                last != null ? last.getMessage() : null, refusalKey, refusalParams,
                textOf(meta.get("errorCode")), textOf(meta.get("messageKey")), paramsOf(meta.get("params")));
    }

    private static String textOf(Object value) {
        return value instanceof String text ? text : null;
    }

    /** The logged params as strings; {@code null} for entries logged without them. */
    private static Map<String, String> paramsOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Map<String, String> params = new LinkedHashMap<>();
        map.forEach((key, val) -> params.put(String.valueOf(key), String.valueOf(val)));
        return params;
    }

    private List<SkillRef> skillRefs(java.util.Set<String> ids) {
        Map<String, Skill> found = skillRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Skill::getId, Function.identity()));
        return ids.stream().sorted()
                .map(id -> new SkillRef(id, found.containsKey(id) ? found.get(id).getName() : null))
                .toList();
    }

    private static TreeSet<String> usedSkillIds(Workflow workflow) {
        TreeSet<String> ids = new TreeSet<>();
        if (workflow.getSealedSkillHashes() != null) {
            try {
                ids.addAll(WorkflowSkillPins.fromJson(workflow.getSealedSkillHashes()).keySet());
            } catch (RuntimeException e) {
                // fall back to the current references below
            }
        }
        if (workflow.getActivatedSkills() != null) {
            workflow.getActivatedSkills().stream().map(Skill::getId).filter(Objects::nonNull).forEach(ids::add);
        }
        if (workflow.getBlocks() != null) {
            for (BlockDefinition block : workflow.getBlocks()) {
                if (block.getSkillIds() != null) {
                    block.getSkillIds().stream().filter(Objects::nonNull).forEach(ids::add);
                }
            }
        }
        return ids;
    }
}

package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundleImportRequest.TargetProject;
import com.automation.dto.bundle.BundleImportResult;
import com.automation.dto.bundle.BundleImportResult.Renamed;
import com.automation.dto.bundle.BundleImportResult.Skipped;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundleProject;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.service.chain.ChainService;
import com.automation.service.project.ProjectService;
import com.automation.service.scheduler.SchedulerService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The writes of a bundle import, in the order their references need: project → workflows → their
 * sub-workflow references → chains → schedules (always disabled). Runs inside the caller's
 * transaction and only through the regular services — workflows via {@link WorkflowService} with
 * {@link ChangeSource#IMPORT}, so every definition gets its version row and none is sealed. A
 * failure anywhere throws and the caller's transaction rolls everything back.
 */
@Component
class BundleImportWriter {

    private static final TypeReference<List<BlockDefinition>> BLOCKS = new TypeReference<>() {};
    private static final String REFS_RESOLVED = "Bundle import: sub-workflow references resolved";

    private final ProjectService projectService;
    private final WorkflowService workflowService;
    private final ChainService chainService;
    private final SchedulerService schedulerService;
    private final ObjectMapper objectMapper;

    BundleImportWriter(ProjectService projectService, WorkflowService workflowService, ChainService chainService,
                       SchedulerService schedulerService, ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.workflowService = workflowService;
        this.chainService = chainService;
        this.schedulerService = schedulerService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param skillIdByName skills of this instance by name
     * @param warnings      pre-computed warnings; appended to
     */
    BundleImportResult write(ProjectBundle bundle, TargetProject target, boolean rename,
                             Map<String, String> skillIdByName, boolean chainsEnabled, Set<String> warnings) {
        Project project = targetProject(bundle.project(), target);
        Map<String, String> existingByName = new HashMap<>();
        if (ProjectBundleImportService.MODE_EXISTING.equals(target.mode())) {
            workflowService.findByProjectId(project.getId()).forEach(w -> existingByName.putIfAbsent(w.getName(), w.getId()));
        }
        Set<String> takenNames = new HashSet<>(existingByName.keySet());
        List<Skipped> skipped = new ArrayList<>();
        List<Renamed> renamed = new ArrayList<>();

        Map<String, String> idByKey = new HashMap<>();
        Map<String, List<Map<String, Object>>> blocksWithRefs = new LinkedHashMap<>();
        for (BundleWorkflow w : bundle.workflows()) {
            BundleDefinition d = w.definition();
            String name = d.name();
            if (existingByName.containsKey(name)) {
                if (!rename) {
                    idByKey.put(w.key(), existingByName.get(name));
                    skipped.add(new Skipped("workflow", w.key(), "name exists"));
                    continue;
                }
                name = freeName(name, takenNames);
                renamed.add(new Renamed("workflow", w.key(), d.name(), name));
            }
            takenNames.add(name);
            List<Map<String, Object>> blocks = blocksWithSkillIds(d, skillIdByName);
            Workflow created = workflowService.create(new CreateWorkflowRequest(project.getId(), name,
                    d.description(), d.icon(), toBlocks(blocks), skillIds(d, skillIdByName), d.instructions(),
                    d.model(), d.provider(), null, d.metadata(), d.defaultContext(), d.version(), d.effort()),
                    ChangeSource.IMPORT);
            idByKey.put(w.key(), created.getId());
            if (!BundleBlocks.workflowRefKeys(blocks).isEmpty()) {
                blocksWithRefs.put(created.getId(), blocks);
            }
        }
        // Second step: the new ids exist only now.
        blocksWithRefs.forEach((id, blocks) -> workflowService.update(id, new UpdateWorkflowRequest(null, null, null,
                toBlocks(resolveRefs(blocks, idByKey)), null, null, null, null, null, REFS_RESOLVED), ChangeSource.IMPORT));

        Map<String, String> chainIdByKey = new HashMap<>();
        int chains = 0;
        for (BundleChain c : bundle.chains()) {
            if (!chainsEnabled) {
                skipped.add(new Skipped("chain", c.key(), "module chains is off"));
                continue;
            }
            List<ChainEntryRequest> entries = c.entries().stream().map(e -> new ChainEntryRequest(
                    idByKey.get(e.workflowKey()), e.label(), e.sortOrder(), e.stopOnFailure(), e.inputContext(),
                    e.timeoutMinutes())).toList();
            chainIdByKey.put(c.key(), chainService.create(new CreateChainRequest(c.name(), c.description(), c.icon(),
                    entries, c.stopOnFailure())).getId());
            chains++;
        }

        int schedules = 0;
        for (BundleSchedule s : bundle.schedules()) {
            String chainKey = s.target().chainKey();
            if (chainKey != null && !chainIdByKey.containsKey(chainKey)) {
                skipped.add(new Skipped("schedule", s.target().label(), "chain was skipped"));
                continue;
            }
            ScheduledWorkflow created = schedulerService.create(
                    chainKey == null ? idByKey.get(s.target().workflowKey()) : null,
                    chainKey == null ? null : chainIdByKey.get(chainKey),
                    chainKey == null ? "WORKFLOW" : "CHAIN", s.cronExpression(), s.description(), s.inputContext(),
                    s.scheduleType(), s.scheduledAt(), s.delayExpression(), s.endDate(), s.maxRuns(),
                    s.conflictBehavior(), s.catchUpOnResume());
            schedulerService.disable(created.getId()); // never runs before the user looked at it
            schedules++;
        }

        int workflows = bundle.workflows().size() - (int) skipped.stream().filter(x -> "workflow".equals(x.kind())).count();
        return new BundleImportResult(project.getId(), new BundleImportResult.Created(workflows, chains, schedules),
                skipped, renamed, List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private Project targetProject(BundleProject data, TargetProject target) {
        if (ProjectBundleImportService.MODE_EXISTING.equals(target.mode())) {
            return projectService.findById(target.projectId());
        }
        String name = target.name() == null || target.name().isBlank() ? data.name() : target.name().strip();
        ProjectType type = data.type() == null ? ProjectType.PERSONAL : ProjectType.valueOf(data.type().toUpperCase(Locale.ROOT));
        return projectService.createWithOptionalPath(new CreateProjectRequest(name, data.subtitle(), data.description(),
                type, target.path() == null ? "" : target.path().strip(),
                data.config() == null ? null : new HashMap<>(data.config()), data.aiContext()));
    }

    /** {@code @@skill:<name>@@} in the blocks' {@code skillIds} → the id here; unknown skills are dropped. */
    private static List<Map<String, Object>> blocksWithSkillIds(BundleDefinition d, Map<String, String> skillIdByName) {
        List<Map<String, Object>> blocks = d.blocks() == null ? new ArrayList<>()
                : BundleStrings.copy(d.blocks(), "blocks", BundleStrings.IDENTITY);
        BundleBlocks.mapSkillIds(blocks, value -> {
            Matcher m = BundleBlocks.SKILL_REF.matcher(value);
            return m.matches() ? skillIdByName.get(m.group(1)) : value;
        });
        return blocks;
    }

    private static List<String> skillIds(BundleDefinition d, Map<String, String> skillIdByName) {
        if (d.activatedSkillNames() == null) {
            return List.of();
        }
        return d.activatedSkillNames().stream().map(skillIdByName::get).filter(id -> id != null).distinct().toList();
    }

    private static List<Map<String, Object>> resolveRefs(List<Map<String, Object>> blocks, Map<String, String> idByKey) {
        return BundleStrings.copy(blocks, "blocks", (value, field) -> {
            Matcher m = BundleBlocks.WORKFLOW_REF.matcher(value);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(idByKey.getOrDefault(m.group(1), m.group())));
            }
            m.appendTail(out);
            return out.toString();
        });
    }

    private List<BlockDefinition> toBlocks(List<Map<String, Object>> blocks) {
        return objectMapper.convertValue(blocks, BLOCKS);
    }

    /** {@code Name (Import)}, {@code Name (Import 2)}, … — the first one not taken. */
    static String freeName(String name, Set<String> taken) {
        String candidate = name + " (Import)";
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = name + " (Import " + n + ")";
        }
        return candidate;
    }
}

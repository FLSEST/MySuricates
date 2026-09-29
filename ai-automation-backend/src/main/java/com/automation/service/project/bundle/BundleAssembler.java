package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleChainEntry;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundlePath;
import com.automation.dto.bundle.ProjectBundle.BundleProject;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.dto.bundle.ProjectBundle.BundleTarget;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the bundle of one project from already loaded entities — pure, no repository, so the rules
 * (keys, references, path tokens) are unit-testable. Loading is {@link ProjectBundleExportService}.
 */
final class BundleAssembler {

    private static final TypeReference<List<Map<String, Object>>> BLOCK_LIST = new TypeReference<>() {};

    /**
     * @param chains         every chain of the instance; only those whose entries all name workflows of
     *                       this project are taken
     * @param schedules      the schedules on this project's workflows and chains
     * @param skillNameById  names of the skills the workflows use
     */
    record Source(Project project, List<Workflow> workflows, List<WorkflowChain> chains,
                  List<ScheduledWorkflow> schedules, Map<String, String> skillNameById,
                  boolean chainsEnabled, String appVersion, Instant exportedAt) {}

    private final ObjectMapper objectMapper;

    BundleAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProjectBundle assemble(Source source) {
        List<String> warnings = new ArrayList<>();
        Set<String> skillNames = new TreeSet<>();

        List<Workflow> workflows = new ArrayList<>(source.workflows());
        workflows.sort(Comparator.comparing(Workflow::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Workflow::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        BundleKeys workflowKeys = new BundleKeys("workflow");
        Map<String, String> keyById = new HashMap<>();
        workflows.forEach(w -> keyById.put(w.getId(), workflowKeys.next(w.getName())));

        List<BundleWorkflow> bundleWorkflows = new ArrayList<>();
        for (Workflow w : workflows) {
            bundleWorkflows.add(new BundleWorkflow(keyById.get(w.getId()),
                    definition(w, keyById, source.skillNameById(), skillNames, warnings)));
        }

        List<WorkflowChain> chains = source.chains().stream()
                .filter(c -> c.getEntries() != null && !c.getEntries().isEmpty()
                        && c.getEntries().stream().allMatch(e -> keyById.containsKey(e.getWorkflowId())))
                .sorted(Comparator.comparing(WorkflowChain::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (!source.chainsEnabled() && !chains.isEmpty()) {
            warnings.add("Module chains is off — " + chains.size() + " chain(s) were not exported.");
            chains = List.of();
        }
        BundleKeys chainKeys = new BundleKeys("chain");
        Map<String, String> chainKeyById = new HashMap<>();
        List<BundleChain> bundleChains = new ArrayList<>();
        for (WorkflowChain c : chains) {
            String key = chainKeys.next(c.getName());
            chainKeyById.put(c.getId(), key);
            bundleChains.add(new BundleChain(key, c.getName(), c.getDescription(), c.getIcon(), c.isStopOnFailure(),
                    c.getEntries().stream().map(e -> entry(e, keyById)).toList()));
        }

        List<BundleSchedule> schedules = source.schedules().stream()
                .map(s -> schedule(s, keyById, chainKeyById))
                .filter(s -> s != null)
                .sorted(Comparator.comparing((BundleSchedule s) -> s.target().label())
                        .thenComparing(s -> String.valueOf(s.cronExpression())))
                .toList();

        Project p = source.project();
        ProjectBundle raw = new ProjectBundle(ProjectBundle.FORMAT, ProjectBundle.FORMAT_VERSION, source.exportedAt(),
                source.appVersion(),
                new BundleProject(p.getName(), p.getSubtitle(), p.getDescription(),
                        p.getType() == null ? null : p.getType().name(), p.getAiContext(),
                        p.getConfig() == null ? Map.of() : BundleStrings.copy(p.getConfig(), "", BundleStrings.IDENTITY)),
                bundleWorkflows, bundleChains, schedules, List.of(), List.copyOf(skillNames),
                List.copyOf(new LinkedHashSet<>(warnings)));
        return tokenizePaths(raw, p.getPath());
    }

    /**
     * Two passes over the same content: collect, then replace. The project path becomes
     * {@code {{ project_path }}} only in blocks and instructions — the text the executor resolves;
     * context values and metadata are not resolved again, so there it gets a token like any path.
     */
    static ProjectBundle tokenizePaths(ProjectBundle raw, String projectPath) {
        PathTokenizer tokenizer = new PathTokenizer(projectPath);
        ProjectBundle placeholders = BundleContent.rewrite(raw, (value, owner, field) -> {
            String hint = owner + " › " + field;
            if (BundleContent.SOURCE_PATH_FIELD.equals(field)) {
                tokenizer.collectWhole(value, hint);
                return value;
            }
            String text = isResolvedAtRunTime(field) ? tokenizer.withProjectPlaceholder(value) : value;
            tokenizer.collect(text, hint);
            return text;
        });
        List<BundlePath> paths = tokenizer.finish();
        ProjectBundle tokenized = BundleContent.rewrite(placeholders, (value, owner, field) ->
                BundleContent.SOURCE_PATH_FIELD.equals(field) ? tokenizer.replaceWhole(value) : tokenizer.replace(value));
        return new ProjectBundle(tokenized.format(), tokenized.formatVersion(), tokenized.exportedAt(),
                tokenized.appVersion(), tokenized.project(), tokenized.workflows(), tokenized.chains(),
                tokenized.schedules(), paths, tokenized.skillNames(), tokenized.warnings());
    }

    private static boolean isResolvedAtRunTime(String field) {
        return field.startsWith("blocks") || field.equals("instructions");
    }

    private BundleDefinition definition(Workflow w, Map<String, String> keyById, Map<String, String> skillNameById,
                                        Set<String> skillNames, List<String> warnings) {
        List<Map<String, Object>> blocks = objectMapper.convertValue(
                w.getBlocks() == null ? List.of() : w.getBlocks(), BLOCK_LIST);
        warnings.addAll(BundleBlocks.exportWorkflowRefs(blocks, keyById, w.getName()));
        BundleBlocks.mapSkillIds(blocks, id -> {
            String name = skillNameById.get(id);
            if (name == null) {
                warnings.add("Workflow '" + w.getName() + "' uses an unknown skill (" + id + ") — left out.");
                return null;
            }
            skillNames.add(name);
            return BundleBlocks.skillRef(name);
        });
        List<String> activated = w.getActivatedSkills() == null ? List.of()
                : w.getActivatedSkills().stream().map(Skill::getName).sorted().toList();
        skillNames.addAll(activated);
        return new BundleDefinition(w.getName(), w.getDescription(), w.getIcon(), blocks, w.getInstructions(),
                w.getModel(), w.getProvider(), w.getEffort(), w.getVersion(),
                BundleStrings.copy(w.getDefaultContext() == null ? Map.of() : w.getDefaultContext(), "", BundleStrings.IDENTITY),
                BundleStrings.copy(w.getMetadata() == null ? Map.of() : w.getMetadata(), "", BundleStrings.IDENTITY),
                activated);
    }

    private static BundleChainEntry entry(ChainEntry e, Map<String, String> keyById) {
        return new BundleChainEntry(keyById.get(e.getWorkflowId()), e.getLabel(), e.getSortOrder(),
                e.isStopOnFailure(), BundleStrings.copy(e.getInputContext() == null ? Map.of() : e.getInputContext(),
                "", BundleStrings.IDENTITY), e.getTimeoutMinutes());
    }

    private static BundleSchedule schedule(ScheduledWorkflow s, Map<String, String> keyById,
                                           Map<String, String> chainKeyById) {
        BundleTarget target;
        if (s.getTargetType() == ScheduledWorkflow.TargetType.CHAIN) {
            String key = s.getChain() == null ? null : chainKeyById.get(s.getChain().getId());
            target = key == null ? null : new BundleTarget(null, key);
        } else {
            String key = s.getWorkflow() == null ? null : keyById.get(s.getWorkflow().getId());
            target = key == null ? null : new BundleTarget(key, null);
        }
        if (target == null) {
            return null; // target not part of the bundle (chain not exported)
        }
        return new BundleSchedule(target, s.getCronExpression(),
                s.getScheduleType() == null ? null : s.getScheduleType().name(), s.getScheduledAt(),
                s.getDelayExpression(), s.getDescription(),
                BundleStrings.copy(s.getInputContext() == null ? Map.of() : s.getInputContext(), "", BundleStrings.IDENTITY),
                s.getConflictBehavior() == null ? null : s.getConflictBehavior().name(), s.isCatchUpOnResume(),
                s.getMaxRuns() > 0 ? s.getMaxRuns() : null, s.getEndDate());
    }
}

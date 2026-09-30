package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleChainEntry;
import com.automation.dto.bundle.ProjectBundle.BundlePath;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import org.springframework.scheduling.support.CronExpression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a bundle before anything is shown or written: format and version, required fields, unique
 * keys, and that every reference ({@code workflowKey}, {@code chainKey}, {@code @@workflow:<key>@@},
 * {@code @@path:N@@}) resolves inside the bundle. All problems are reported at once as one
 * {@link IllegalArgumentException} (400).
 */
final class BundleValidator {

    static final Pattern PATH_TOKEN = Pattern.compile("@@path:\\d+@@");
    private static final int MAX_REPORTED = 15;

    private BundleValidator() {
    }

    static void validate(ProjectBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("Invalid bundle: 'bundle' is missing");
        }
        if (!ProjectBundle.FORMAT.equals(bundle.format())) {
            throw new IllegalArgumentException("Invalid bundle: format must be '" + ProjectBundle.FORMAT
                    + "', got '" + bundle.format() + "'");
        }
        if (bundle.formatVersion() == null || bundle.formatVersion() != ProjectBundle.FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported bundle formatVersion " + bundle.formatVersion()
                    + " (supported: " + ProjectBundle.FORMAT_VERSION + ")");
        }
        List<String> problems = new ArrayList<>();
        checkProject(bundle, problems);
        Set<String> workflowKeys = checkWorkflows(bundle.workflows(), problems);
        Set<String> chainKeys = checkChains(bundle.chains(), workflowKeys, problems);
        checkSchedules(bundle.schedules(), workflowKeys, chainKeys, problems);
        checkPaths(bundle, problems);
        if (!problems.isEmpty()) {
            List<String> shown = problems.size() > MAX_REPORTED ? problems.subList(0, MAX_REPORTED) : problems;
            throw new IllegalArgumentException("Invalid bundle: " + String.join("; ", shown)
                    + (problems.size() > MAX_REPORTED ? "; … (" + problems.size() + " problems)" : ""));
        }
    }

    private static void checkProject(ProjectBundle bundle, List<String> problems) {
        if (bundle.project() == null || isBlank(bundle.project().name())) {
            problems.add("project.name is required");
            return;
        }
        String type = bundle.project().type();
        if (type != null && !isEnum(ProjectType.class, type)) {
            problems.add("project.type '" + type + "' is unknown");
        }
    }

    private static Set<String> checkWorkflows(List<BundleWorkflow> workflows, List<String> problems) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < workflows.size(); i++) {
            BundleWorkflow w = workflows.get(i);
            if (w == null || isBlank(w.key())) {
                problems.add("workflows[" + i + "].key is required");
                continue;
            }
            if (!keys.add(w.key())) {
                problems.add("duplicate workflow key '" + w.key() + "'");
            }
            if (w.definition() == null || isBlank(w.definition().name())) {
                problems.add("workflow '" + w.key() + "': definition.name is required");
            }
        }
        for (BundleWorkflow w : workflows) {
            if (w != null && w.definition() != null) {
                for (String ref : BundleBlocks.workflowRefKeys(w.definition().blocks())) {
                    if (!keys.contains(ref)) {
                        problems.add("workflow '" + w.key() + "' references unknown workflow key '" + ref + "'");
                    }
                }
            }
        }
        return keys;
    }

    private static Set<String> checkChains(List<BundleChain> chains, Set<String> workflowKeys, List<String> problems) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < chains.size(); i++) {
            BundleChain c = chains.get(i);
            if (c == null || isBlank(c.key())) {
                problems.add("chains[" + i + "].key is required");
                continue;
            }
            if (!keys.add(c.key())) {
                problems.add("duplicate chain key '" + c.key() + "'");
            }
            if (isBlank(c.name())) {
                problems.add("chain '" + c.key() + "': name is required");
            }
            if (c.entries() == null || c.entries().isEmpty()) {
                problems.add("chain '" + c.key() + "': entries must not be empty");
                continue;
            }
            for (BundleChainEntry e : c.entries()) {
                if (e == null || !workflowKeys.contains(e.workflowKey())) {
                    problems.add("chain '" + c.key() + "' references unknown workflow key '"
                            + (e == null ? null : e.workflowKey()) + "'");
                }
            }
        }
        return keys;
    }

    private static void checkSchedules(List<BundleSchedule> schedules, Set<String> workflowKeys,
                                       Set<String> chainKeys, List<String> problems) {
        for (int i = 0; i < schedules.size(); i++) {
            BundleSchedule s = schedules.get(i);
            String where = "schedules[" + i + "]";
            if (s == null || s.target() == null || (s.target().workflowKey() == null) == (s.target().chainKey() == null)) {
                problems.add(where + ": target needs exactly one of workflowKey and chainKey");
                continue;
            }
            if (s.target().workflowKey() != null && !workflowKeys.contains(s.target().workflowKey())) {
                problems.add(where + " references unknown workflow key '" + s.target().workflowKey() + "'");
            }
            if (s.target().chainKey() != null && !chainKeys.contains(s.target().chainKey())) {
                problems.add(where + " references unknown chain key '" + s.target().chainKey() + "'");
            }
            String type = s.scheduleType() == null ? "CRON" : s.scheduleType();
            if (!isEnum(ScheduledWorkflow.ScheduleType.class, type)) {
                problems.add(where + ": scheduleType '" + type + "' is unknown");
            } else if ("CRON".equalsIgnoreCase(type)
                    && (s.cronExpression() == null || !CronExpression.isValidExpression(s.cronExpression()))) {
                problems.add(where + ": invalid cron expression '" + s.cronExpression() + "'");
            }
            if (s.conflictBehavior() != null && !isEnum(ScheduledWorkflow.ConflictBehavior.class, s.conflictBehavior())) {
                problems.add(where + ": conflictBehavior '" + s.conflictBehavior() + "' is unknown");
            }
        }
    }

    /** {@code paths[]} is well-formed, and every token used in the content is listed there. */
    private static void checkPaths(ProjectBundle bundle, List<String> problems) {
        Set<String> listed = new HashSet<>();
        for (BundlePath p : bundle.paths()) {
            if (p == null || p.token() == null || !PATH_TOKEN.matcher(p.token()).matches()) {
                problems.add("paths: invalid token '" + (p == null ? null : p.token()) + "'");
            } else if (!listed.add(p.token())) {
                problems.add("paths: duplicate token '" + p.token() + "'");
            }
        }
        Set<String> unlisted = new java.util.TreeSet<>();
        BundleContent.scan(bundle, (value, owner, field) -> {
            Matcher m = PATH_TOKEN.matcher(value);
            while (m.find()) {
                if (!listed.contains(m.group())) {
                    unlisted.add(m.group());
                }
            }
            return value;
        });
        unlisted.forEach(t -> problems.add("path token " + t + " is used but not listed in paths"));
    }

    private static <E extends Enum<E>> boolean isEnum(Class<E> type, String value) {
        try {
            Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

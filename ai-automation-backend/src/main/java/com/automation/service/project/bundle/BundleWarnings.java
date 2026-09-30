package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

/** The warnings preview and import agree on — same text in both, so the UI shows each once. */
final class BundleWarnings {

    static final String CHAINS_OFF = "Module chains is off — the bundle's chains are skipped.";

    private BundleWarnings() {
    }

    static String missingSkill(String name) {
        return "Skill '" + name + "' does not exist on this instance — workflows are created without it.";
    }

    /**
     * @param knownSkills   skill names of this instance
     * @param chainsEnabled whether module chains is on
     */
    static List<String> of(ProjectBundle bundle, Set<String> knownSkills, boolean chainsEnabled) {
        Set<String> warnings = new LinkedHashSet<>();
        for (BundleWorkflow w : bundle.workflows()) {
            BundleBlocks.externalRefs(w.definition().blocks())
                    .forEach(id -> warnings.add(BundleBlocks.externalRefWarning(w.definition().name(), id)));
        }
        warnings.addAll(bundle.warnings());
        referencedSkills(bundle).stream().filter(n -> !knownSkills.contains(n))
                .forEach(n -> warnings.add(missingSkill(n)));
        if (!bundle.chains().isEmpty() && !chainsEnabled) {
            warnings.add(CHAINS_OFF);
        }
        return List.copyOf(warnings);
    }

    /** {@code skillNames}, every {@code activatedSkillNames} entry and every {@code @@skill:<name>@@} in blocks. */
    static Set<String> referencedSkills(ProjectBundle bundle) {
        Set<String> names = new TreeSet<>(bundle.skillNames());
        for (BundleWorkflow w : bundle.workflows()) {
            if (w.definition().activatedSkillNames() != null) {
                names.addAll(w.definition().activatedSkillNames());
            }
            if (w.definition().blocks() != null) {
                BundleStrings.copy(w.definition().blocks(), "", (value, field) -> {
                    Matcher m = BundleBlocks.SKILL_REF.matcher(value);
                    if (m.matches()) {
                        names.add(m.group(1));
                    }
                    return value;
                });
            }
        }
        return names;
    }
}

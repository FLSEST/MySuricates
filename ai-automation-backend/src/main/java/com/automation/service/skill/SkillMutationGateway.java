package com.automation.service.skill;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.Skill;
import com.automation.entity.SkillChangeSource;
import com.automation.entity.SkillVersion;
import com.automation.entity.SkillVersion.Operation;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.repository.SkillRepository;
import com.automation.repository.SkillVersionRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place a skill is written — counterpart of {@code WorkflowMutationGateway}. Every change
 * gets a version row (canonical snapshot, content hash, server-side {@link SkillChangeSource}) and
 * an activity-log entry; {@code SkillWriterAllowlistTest} pins that nothing else calls
 * {@code skillRepository.save/delete}.
 *
 * <p>Being that one place, it is also where a change is refused: a skill that a sealed workflow
 * uses cannot be changed or deleted by a person until the workflow is unsealed. Built-in skills
 * are written from the bundled resources at startup and cannot be refused; when their content
 * changes under a sealed workflow this is logged as a SECURITY event, and {@code RunAdmission}
 * refuses the workflow's runs until it is sealed again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMutationGateway {

    private final SkillRepository skillRepository;
    private final SkillVersionRepository versionRepository;
    private final WorkflowRepository workflowRepository;
    private final SkillContentHasher hasher;
    private final ActivityLogService activityLog;

    /** Creates or updates a skill (capabilities included via cascade). */
    @Transactional
    public Skill save(Skill skill, SkillChangeSource source) {
        boolean existing = skill.getId() != null && skillRepository.existsById(skill.getId());
        if (existing && source != SkillChangeSource.BUILTIN_SEED) {
            requireNotPinned(skill, "changing it");
        }
        Skill saved = skillRepository.save(skill);
        String hash = hasher.hash(saved);
        String snapshot = hasher.snapshotJson(saved);
        Optional<SkillVersion> latest = versionRepository.findTopBySkillIdOrderByVersionNumberDesc(saved.getId());
        if (latest.isPresent() && latest.get().getSnapshot().equals(snapshot)) {
            return saved; // nothing that a version row would describe has changed
        }
        boolean contentChanged = latest.map(v -> !v.getContentHash().equals(hash)).orElse(true);
        SkillVersion version = record(saved.getId(), existing ? Operation.UPDATE : Operation.CREATE, snapshot, hash,
                source, latest);
        logChange(saved, version, source, contentChanged && existing);
        return saved;
    }

    @Transactional
    public void delete(Skill skill, SkillChangeSource source) {
        requireNotPinned(skill, "deleting it");
        String hash = hasher.hash(skill);
        String snapshot = hasher.snapshotJson(skill);
        skillRepository.delete(skill);
        SkillVersion version = record(skill.getId(), Operation.DELETE, snapshot, hash, source,
                versionRepository.findTopBySkillIdOrderByVersionNumberDesc(skill.getId()));
        logChange(skill, version, source, false);
    }

    /** Also called before an edit touches the entity, so a refusal names the skill as it is stored. */
    void requireNotPinned(Skill skill, String operation) {
        List<String> sealed = workflowRepository.findSealedWorkflowNamesUsingSkill(skill.getId());
        if (!sealed.isEmpty()) {
            throw new HardeningException(ErrorCode.SKILL_PINNED_BY_SEAL, "Skill '" + skill.getName()
                    + "' is used by sealed workflow(s) " + String.join(", ", sealed) + " — unseal them before " + operation);
        }
    }

    private SkillVersion record(String skillId, Operation operation, String snapshot, String hash,
                                SkillChangeSource source, Optional<SkillVersion> latest) {
        return versionRepository.save(SkillVersion.builder()
                .skillId(skillId)
                .versionNumber(latest.map(SkillVersion::getVersionNumber).orElse(0) + 1)
                .operation(operation)
                .snapshot(snapshot)
                .contentHash(hash)
                .changeSource(source.key())
                .build());
    }

    private void logChange(Skill skill, SkillVersion version, SkillChangeSource source, boolean contentChangedInPlace) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skillId", skill.getId());
        metadata.put("operation", version.getOperation().name());
        metadata.put("changeSource", source.key());
        metadata.put("versionNumber", version.getVersionNumber());
        metadata.put("contentHash", version.getContentHash());
        String verb = switch (version.getOperation()) {
            case CREATE -> source == SkillChangeSource.IMPORT ? "imported" : "created";
            case UPDATE -> "updated";
            case DELETE -> "deleted";
        };
        if (source == SkillChangeSource.BUILTIN_SEED) {
            if (contentChangedInPlace) {
                warnSealedUsers(skill, metadata);
            }
            if (version.getOperation() == Operation.CREATE) {
                return; // first seed of a bundled skill — the startup summary line covers it
            }
        }
        // Imported content comes from elsewhere: a SECURITY event, like sealing.
        boolean security = source == SkillChangeSource.IMPORT;
        activityLog.log(security ? LogLevel.WARN : LogLevel.INFO, security ? LogCategory.SECURITY : LogCategory.SYSTEM,
                (skill.isBuiltin() ? "Built-in skill " : "Skill ") + verb + ": " + skill.getName(),
                "SkillMutationGateway", skill.getId(), metadata);
        log.info("Skill {} {} ({}, v{} by {})", skill.getName(), verb, skill.getId(), version.getVersionNumber(), source.key());
    }

    private void warnSealedUsers(Skill skill, Map<String, Object> metadata) {
        List<String> sealed = workflowRepository.findSealedWorkflowNamesUsingSkill(skill.getId());
        if (sealed.isEmpty()) {
            return;
        }
        Map<String, Object> withWorkflows = new LinkedHashMap<>(metadata);
        withWorkflows.put("sealedWorkflows", sealed);
        activityLog.log(LogLevel.WARN, LogCategory.SECURITY, "Built-in skill " + skill.getName()
                        + " changed — sealed workflows refuse to run until sealed again: " + String.join(", ", sealed),
                "SkillMutationGateway", skill.getId(), withWorkflows);
        log.warn("Built-in skill {} changed under sealed workflow(s) {}", skill.getName(), sealed);
    }
}

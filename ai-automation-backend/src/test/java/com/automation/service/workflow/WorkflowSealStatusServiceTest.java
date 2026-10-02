package com.automation.service.workflow;

import com.automation.dto.response.WorkflowSealStatusResponse;
import com.automation.entity.ActivityLog;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ActivityLogRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSealStatusServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private ActivityLogRepository activityLogRepository;
    @Mock private WorkflowSkillPins skillPins;

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();
    private WorkflowSealStatusService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowSealStatusService(workflowRepository, skillRepository, activityLogRepository,
                new WorkflowSealVerifier(hasher, skillPins, RunAdmissionTest.unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())));
        lenient().when(activityLogRepository.findBySourceAndTimestampAfterOrderByTimestampDesc(eq("RunAdmission"), any()))
                .thenReturn(List.of());
    }

    private Workflow sealed(String id, String name, String pinsJson) {
        Workflow w = Workflow.builder().id(id).name(name).instructions("go").blocks(new ArrayList<>())
                .activatedSkills(new ArrayList<>()).build();
        w.setSealed(true);
        w.setSealedHash(hasher.hash(w));
        w.setSealedAt(Instant.parse("2026-09-20T08:00:00Z"));
        w.setSealedSkillHashes(pinsJson);
        return w;
    }

    private static ActivityLog refusal(String workflowId, String message, Instant at) {
        return ActivityLog.builder().workflowId(workflowId).message(message).timestamp(at).source("RunAdmission")
                .level(LogLevel.ERROR).category(LogCategory.SECURITY).build();
    }

    @Test
    void intactSealReportsItsPinnedSkillCount() {
        Workflow w = sealed("wf-1", "Deploy", "{\"s1\":\"h1\",\"s2\":\"h2\"}");
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of(w));
        when(skillPins.pins(w)).thenReturn(Map.of("s1", "h1", "s2", "h2"));

        WorkflowSealStatusResponse status = service.statusAll().get(0);

        assertThat(status.state()).isEqualTo("INTACT");
        assertThat(status.problem()).isEqualTo("NONE");
        assertThat(status.skillsPinned()).isTrue();
        assertThat(status.pinnedSkillCount()).isEqualTo(2);
        assertThat(status.sealedAt()).isEqualTo(Instant.parse("2026-09-20T08:00:00Z"));
        assertThat(status.refusedStarts()).isZero();
        assertThat(status.refusalKey()).isNull();
        assertThat(status.refusalParams()).isNull();
    }

    @Test
    void changedSkillMakesTheSealViolatedAndNamesTheSkill() {
        Workflow w = sealed("wf-1", "Deploy", "{\"s1\":\"h1\"}");
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of(w));
        when(skillPins.pins(w)).thenReturn(Map.of("s1", "tampered"));
        when(skillRepository.findAllById(anyCollection())).thenReturn(List.of(
                Skill.builder().id("s1").name("Reviewer").category(SkillCategory.CODE).build()));
        Instant at = Instant.now().minusSeconds(60);
        when(activityLogRepository.findBySourceAndTimestampAfterOrderByTimestampDesc(eq("RunAdmission"), any()))
                .thenReturn(List.of(refusal("wf-1", "Run refused — newest", at), refusal("wf-1", "older", at.minusSeconds(60))));

        WorkflowSealStatusResponse status = service.statusAll().get(0);

        assertThat(status.state()).isEqualTo("VIOLATED");
        assertThat(status.problem()).isEqualTo("SKILLS_CHANGED");
        assertThat(status.changedSkills()).containsExactly(new WorkflowSealStatusResponse.SkillRef("s1", "Reviewer"));
        assertThat(status.refusedStarts()).isEqualTo(2);
        assertThat(status.lastRefusal()).isEqualTo("Run refused — newest");
        assertThat(status.lastRefusedAt()).isEqualTo(at);
        // 2b73ff48: the reason as messageKey + params for the UI; a refusal logged without them stays text
        assertThat(status.refusalKey()).isEqualTo("SEAL_SKILLS_CHANGED");
        assertThat(status.refusalParams()).isEqualTo(Map.of("skills", "s1", "name", "Deploy"));
        assertThat(status.lastRefusalCode()).isNull();
        assertThat(status.lastRefusalKey()).isNull();
        assertThat(status.lastRefusalParams()).isNull();
    }

    /** 2b73ff48: RunAdmission logs code, messageKey and params next to the refusal; the status hands them on. */
    @Test
    void latestRefusalCarriesItsTranslationKeys() {
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of());
        ActivityLog logged = refusal("wf-9", "Run refused — unattended", Instant.now());
        logged.setMetadata(new HashMap<>(Map.of("errorCode", "UNATTENDED_REQUIRES_SEAL",
                "params", Map.of("name", "Nightly", "origin", "SCHEDULE"))));
        when(activityLogRepository.findBySourceAndTimestampAfterOrderByTimestampDesc(eq("RunAdmission"), any()))
                .thenReturn(List.of(logged));
        when(workflowRepository.findIdAndNameByIdIn(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"wf-9", "Nightly"}));

        WorkflowSealStatusResponse status = service.statusAll().get(0);

        assertThat(status.refusalKey()).isNull();
        assertThat(status.lastRefusalCode()).isEqualTo("UNATTENDED_REQUIRES_SEAL");
        assertThat(status.lastRefusalKey()).isNull();
        assertThat(status.lastRefusalParams()).isEqualTo(Map.of("name", "Nightly", "origin", "SCHEDULE"));
    }

    @Test
    void sealFromBeforeSkillPinningIsIntactButNotPinned() {
        Workflow w = sealed("wf-1", "Deploy", null);
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of(w));

        WorkflowSealStatusResponse status = service.statusAll().get(0);

        assertThat(status.state()).isEqualTo("INTACT");
        assertThat(status.skillsPinned()).isFalse();
    }

    @Test
    void unsealedWorkflowWithRefusedStartsIsListedToo() {
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of());
        when(activityLogRepository.findBySourceAndTimestampAfterOrderByTimestampDesc(eq("RunAdmission"), any()))
                .thenReturn(List.of(refusal("wf-9", "Run refused — unattended", Instant.now()), refusal("wf-gone", "x", Instant.now())));
        when(workflowRepository.findIdAndNameByIdIn(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"wf-9", "Nightly"}));

        List<WorkflowSealStatusResponse> all = service.statusAll();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).state()).isEqualTo("NOT_SEALED");
        assertThat(all.get(0).workflowName()).isEqualTo("Nightly");
        assertThat(all.get(0).refusedStarts()).isEqualTo(1);
    }

    @Test
    void skillsAreMappedToTheSealedWorkflowsThatUseThem() {
        Workflow pinned = sealed("wf-1", "Deploy", "{\"s1\":\"h1\"}");
        Workflow legacy = sealed("wf-2", "Audit", null);
        legacy.setActivatedSkills(new ArrayList<>(List.of(Skill.builder().id("s1").name("Reviewer").category(SkillCategory.CODE).build())));
        legacy.setBlocks(new ArrayList<>(List.of(BlockDefinition.builder().type("simple").skillIds(new ArrayList<>(List.of("s2"))).build())));
        when(workflowRepository.findSealedWithSkills()).thenReturn(List.of(pinned, legacy));

        assertThat(service.sealedWorkflowsBySkill()).containsExactly(
                Map.entry("s1", List.of("Audit", "Deploy")),
                Map.entry("s2", List.of("Audit")));
    }
}

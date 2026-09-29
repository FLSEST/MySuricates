package com.automation.service.skill;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.SkillChangeSource;
import com.automation.entity.SkillVersion;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.repository.SkillRepository;
import com.automation.repository.SkillVersionRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillMutationGatewayTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillVersionRepository versionRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ActivityLogService activityLog;

    private final SkillContentHasher hasher = new SkillContentHasher();
    private SkillMutationGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SkillMutationGateway(skillRepository, versionRepository, workflowRepository, hasher, activityLog);
        lenient().when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(versionRepository.save(any(SkillVersion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Skill skill(String aiContext) {
        return Skill.builder().id("skill-1").name("Reviewer").category(SkillCategory.CODE).aiContext(aiContext)
                .capabilities(new ArrayList<>()).build();
    }

    private SkillVersion savedVersion() {
        ArgumentCaptor<SkillVersion> captor = ArgumentCaptor.forClass(SkillVersion.class);
        verify(versionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createRecordsTheFirstVersion() {
        when(skillRepository.existsById("skill-1")).thenReturn(false);
        when(versionRepository.findTopBySkillIdOrderByVersionNumberDesc("skill-1")).thenReturn(Optional.empty());
        Skill skill = skill("ctx");

        gateway.save(skill, SkillChangeSource.USER);

        SkillVersion version = savedVersion();
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getOperation()).isEqualTo(SkillVersion.Operation.CREATE);
        assertThat(version.getChangeSource()).isEqualTo("user");
        assertThat(version.getContentHash()).isEqualTo(hasher.hash(skill));
        assertThat(version.getSnapshot()).isEqualTo(hasher.snapshotJson(skill));
        verify(activityLog).log(eq(LogLevel.INFO), eq(LogCategory.SYSTEM), eq("Skill created: Reviewer"),
                eq("SkillMutationGateway"), eq("skill-1"), any());
        verifyNoInteractions(workflowRepository);
    }

    @Test
    void importIsASecurityEvent() {
        when(versionRepository.findTopBySkillIdOrderByVersionNumberDesc("skill-1")).thenReturn(Optional.empty());

        gateway.save(skill("ctx"), SkillChangeSource.IMPORT);

        assertThat(savedVersion().getChangeSource()).isEqualTo("import");
        verify(activityLog).log(eq(LogLevel.WARN), eq(LogCategory.SECURITY), eq("Skill imported: Reviewer"),
                anyString(), eq("skill-1"), any());
    }

    @Test
    void userChangeOfASkillUsedByASealedWorkflowIsRefused() {
        when(skillRepository.existsById("skill-1")).thenReturn(true);
        when(workflowRepository.findSealedWorkflowNamesUsingSkill("skill-1")).thenReturn(List.of("Deploy"));

        assertThatThrownBy(() -> gateway.save(skill("changed"), SkillChangeSource.USER))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.SKILL_PINNED_BY_SEAL))
                .satisfies(e -> assertThat(((HardeningException) e).getParams()).containsEntry("workflows", "Deploy").containsKey("name"))
                .hasMessageContaining("Deploy");
        verify(skillRepository, never()).save(any());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void deleteOfASkillUsedByASealedWorkflowIsRefused() {
        when(workflowRepository.findSealedWorkflowNamesUsingSkill("skill-1")).thenReturn(List.of("Deploy"));

        assertThatThrownBy(() -> gateway.delete(skill("ctx"), SkillChangeSource.USER))
                .isInstanceOf(HardeningException.class).hasMessageContaining("unseal");
        verify(skillRepository, never()).delete(any());
    }

    @Test
    void deleteRecordsTheDeletedStateAsNextVersion() {
        Skill skill = skill("ctx");
        when(workflowRepository.findSealedWorkflowNamesUsingSkill("skill-1")).thenReturn(List.of());
        when(versionRepository.findTopBySkillIdOrderByVersionNumberDesc("skill-1"))
                .thenReturn(Optional.of(SkillVersion.builder().versionNumber(3).contentHash("x").snapshot("{}").build()));

        gateway.delete(skill, SkillChangeSource.USER);

        verify(skillRepository).delete(skill);
        SkillVersion version = savedVersion();
        assertThat(version.getVersionNumber()).isEqualTo(4);
        assertThat(version.getOperation()).isEqualTo(SkillVersion.Operation.DELETE);
        assertThat(version.getSnapshot()).isEqualTo(hasher.snapshotJson(skill));
    }

    @Test
    void builtinUpdateUnderASealedWorkflowGoesThroughButIsLoggedAsSecurityEvent() {
        Skill skill = skill("new bundled context");
        skill.setBuiltin(true);
        when(skillRepository.existsById("skill-1")).thenReturn(true);
        when(versionRepository.findTopBySkillIdOrderByVersionNumberDesc("skill-1"))
                .thenReturn(Optional.of(SkillVersion.builder().versionNumber(1).contentHash("old").snapshot("old").build()));
        when(workflowRepository.findSealedWorkflowNamesUsingSkill("skill-1")).thenReturn(List.of("Deploy"));

        gateway.save(skill, SkillChangeSource.BUILTIN_SEED);

        verify(skillRepository).save(skill);
        assertThat(savedVersion().getOperation()).isEqualTo(SkillVersion.Operation.UPDATE);
        verify(activityLog).log(eq(LogLevel.WARN), eq(LogCategory.SECURITY), argThat(m -> m.contains("Deploy")),
                eq("SkillMutationGateway"), eq("skill-1"), argThat(meta -> List.of("Deploy").equals(meta.get("sealedWorkflows"))));
        verify(activityLog).log(eq(LogLevel.INFO), eq(LogCategory.SYSTEM), eq("Built-in skill updated: Reviewer"),
                anyString(), eq("skill-1"), any());
    }

    @Test
    void firstSeedOfABuiltinWritesAVersionButNoActivityEntry() {
        Skill skill = skill("bundled");
        skill.setBuiltin(true);
        when(versionRepository.findTopBySkillIdOrderByVersionNumberDesc("skill-1")).thenReturn(Optional.empty());

        gateway.save(skill, SkillChangeSource.BUILTIN_SEED);

        assertThat(savedVersion().getChangeSource()).isEqualTo("builtin-seed");
        verifyNoInteractions(activityLog, workflowRepository);
    }

    @Test
    void unchangedSaveWritesNoVersion() {
        Skill skill = skill("ctx");
        when(versionRepository.findTopBySkillIdOrderByVersionNumberDesc("skill-1")).thenReturn(Optional.of(
                SkillVersion.builder().versionNumber(1).contentHash(hasher.hash(skill)).snapshot(hasher.snapshotJson(skill)).build()));

        gateway.save(skill, SkillChangeSource.BUILTIN_SEED);

        verify(versionRepository, never()).save(any());
        verifyNoInteractions(activityLog);
    }
}

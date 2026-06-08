package com.automation.service.learning;

import com.automation.dto.response.PlatformStatsResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.*;
import com.automation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformStatsServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private WorkflowTemplateRepository templateRepository;
    @Mock private ScheduledWorkflowRepository scheduleRepository;
    @Mock private WebhookTriggerRepository webhookRepository;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private TodoTriggerRepository todoTriggerRepository;
    @Mock private CompletionTriggerRepository completionTriggerRepository;
    @Mock private WorkflowMemoryRepository memoryRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private WishRepository wishRepository;
    @Mock private AssistantSessionRepository assistantSessionRepository;

    private PlatformStatsService service;

    @BeforeEach
    void setUp() {
        service = new PlatformStatsService(
                projectRepository, workflowRepository, skillRepository, templateRepository,
                scheduleRepository, webhookRepository, chainRepository, chainRunRepository,
                todoTriggerRepository, completionTriggerRepository, memoryRepository,
                todoRepository, wishRepository, assistantSessionRepository);
        ReflectionTestUtils.setField(service, "workflowPoolPath", "nonexistent-dir");
    }

    @Test
    void getPlatformStats_allEmpty_returnsZeros() {
        stubAllEmpty();

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.activeProjects()).isZero();
        assertThat(result.archivedProjects()).isZero();
        assertThat(result.totalWorkflows()).isZero();
        assertThat(result.workflowPoolSize()).isZero();
        assertThat(result.totalSkills()).isZero();
        assertThat(result.builtinSkills()).isZero();
        assertThat(result.customSkills()).isZero();
        assertThat(result.topSkillsByUsage()).isEmpty();
        assertThat(result.totalTemplates()).isZero();
        assertThat(result.builtinTemplates()).isZero();
        assertThat(result.customTemplates()).isZero();
        assertThat(result.activeSchedules()).isZero();
        assertThat(result.totalSchedules()).isZero();
        assertThat(result.activeWebhooks()).isZero();
        assertThat(result.totalWebhooks()).isZero();
        assertThat(result.chainStats().totalChains()).isZero();
        assertThat(result.activeTodoTriggers()).isZero();
        assertThat(result.activeCompletionTriggers()).isZero();
        assertThat(result.activeMemoryEntries()).isZero();
        assertThat(result.todoStats().open()).isZero();
        assertThat(result.wishStats().total()).isZero();
        assertThat(result.assistantStats().totalSessions()).isZero();
        assertThat(result.assistantStats().avgMessagesPerSession()).isZero();
    }

    @Test
    void getPlatformStats_projectCounts() {
        stubAllEmpty();
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(
                List.of(Project.builder().build(), Project.builder().build()));
        when(projectRepository.findByArchivedAtIsNotNull()).thenReturn(
                List.of(Project.builder().build()));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.activeProjects()).isEqualTo(2);
        assertThat(result.archivedProjects()).isEqualTo(1);
    }

    @Test
    void getPlatformStats_skillCounts_builtinVsCustom() {
        stubAllEmpty();
        Skill builtin = Skill.builder().id("s1").name("Lint").builtin(true)
                .category(SkillCategory.CODE).build();
        Skill custom = Skill.builder().id("s2").name("MySkill").builtin(false).build();
        when(skillRepository.findAll()).thenReturn(List.of(builtin, custom));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.totalSkills()).isEqualTo(2);
        assertThat(result.builtinSkills()).isEqualTo(1);
        assertThat(result.customSkills()).isEqualTo(1);
    }

    @Test
    void getPlatformStats_topSkillsByUsage_onlyIncludesSkillsWithWorkflows() {
        stubAllEmpty();
        Workflow wf = Workflow.builder().id("wf1").name("test").build();
        Skill used = Skill.builder().id("s1").name("Used").builtin(true)
                .category(SkillCategory.CODE).workflows(List.of(wf)).build();
        Skill unused = Skill.builder().id("s2").name("Unused").builtin(false)
                .workflows(List.of()).build();
        when(skillRepository.findAll()).thenReturn(List.of(used, unused));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.topSkillsByUsage()).hasSize(1);
        assertThat(result.topSkillsByUsage().get(0).skillName()).isEqualTo("Used");
        assertThat(result.topSkillsByUsage().get(0).workflowCount()).isEqualTo(1);
        assertThat(result.topSkillsByUsage().get(0).category()).isEqualTo("CODE");
    }

    @Test
    void getPlatformStats_topSkillsByUsage_nullCategoryBecomesEmpty() {
        stubAllEmpty();
        Workflow wf = Workflow.builder().id("wf1").name("test").build();
        Skill skill = Skill.builder().id("s1").name("NoCategory")
                .category(null).workflows(List.of(wf)).build();
        when(skillRepository.findAll()).thenReturn(List.of(skill));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.topSkillsByUsage()).hasSize(1);
        assertThat(result.topSkillsByUsage().get(0).category()).isEmpty();
    }

    @Test
    void getPlatformStats_templateCounts() {
        stubAllEmpty();
        when(templateRepository.count()).thenReturn(10L);
        when(templateRepository.findByBuiltInTrue()).thenReturn(
                List.of(WorkflowTemplate.builder().build(),
                        WorkflowTemplate.builder().build(),
                        WorkflowTemplate.builder().build()));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.totalTemplates()).isEqualTo(10);
        assertThat(result.builtinTemplates()).isEqualTo(3);
        assertThat(result.customTemplates()).isEqualTo(7);
    }

    @Test
    void getPlatformStats_scheduleCounts() {
        stubAllEmpty();
        when(scheduleRepository.count()).thenReturn(5L);
        when(scheduleRepository.findByEnabledTrue()).thenReturn(
                List.of(ScheduledWorkflow.builder().build(),
                        ScheduledWorkflow.builder().build()));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.totalSchedules()).isEqualTo(5);
        assertThat(result.activeSchedules()).isEqualTo(2);
    }

    @Test
    void getPlatformStats_webhookCounts() {
        stubAllEmpty();
        when(webhookRepository.count()).thenReturn(3L);
        when(webhookRepository.countByEnabledTrue()).thenReturn(2L);

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.totalWebhooks()).isEqualTo(3);
        assertThat(result.activeWebhooks()).isEqualTo(2);
    }

    @Test
    void getPlatformStats_chainStats() {
        stubAllEmpty();
        when(chainRepository.count()).thenReturn(4L);
        when(chainRunRepository.count()).thenReturn(10L);
        when(chainRunRepository.countByStatus(RunStatus.COMPLETED)).thenReturn(7L);
        when(chainRunRepository.countByStatus(RunStatus.FAILED)).thenReturn(2L);

        PlatformStatsResponse result = service.getPlatformStats();

        PlatformStatsResponse.ChainStats cs = result.chainStats();
        assertThat(cs.totalChains()).isEqualTo(4);
        assertThat(cs.totalRuns()).isEqualTo(10);
        assertThat(cs.successfulRuns()).isEqualTo(7);
        assertThat(cs.failedRuns()).isEqualTo(2);
    }

    @Test
    void getPlatformStats_triggerCounts() {
        stubAllEmpty();
        when(todoTriggerRepository.findByEnabledTrue()).thenReturn(
                List.of(TodoTrigger.builder().build()));
        when(completionTriggerRepository.countByEnabledTrue()).thenReturn(3L);

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.activeTodoTriggers()).isEqualTo(1);
        assertThat(result.activeCompletionTriggers()).isEqualTo(3);
    }

    @Test
    void getPlatformStats_memoryEntries() {
        stubAllEmpty();
        when(memoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(
                List.of(WorkflowMemory.builder().build(),
                        WorkflowMemory.builder().build()));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.activeMemoryEntries()).isEqualTo(2);
    }

    @Test
    void getPlatformStats_todoStats() {
        stubAllEmpty();
        when(todoRepository.countGroupedByStatus()).thenReturn(
                new TodoCountResponse(5, 3, 10, 2, 0));
        when(todoRepository.countBySource(Todo.Source.USER)).thenReturn(8L);
        when(todoRepository.countBySource(Todo.Source.AI)).thenReturn(7L);
        when(todoRepository.countBySource(Todo.Source.WORKFLOW)).thenReturn(5L);

        PlatformStatsResponse result = service.getPlatformStats();

        PlatformStatsResponse.TodoStats ts = result.todoStats();
        assertThat(ts.open()).isEqualTo(5);
        assertThat(ts.inProgress()).isEqualTo(3);
        assertThat(ts.done()).isEqualTo(10);
        assertThat(ts.backlog()).isEqualTo(2);
        assertThat(ts.fromUser()).isEqualTo(8);
        assertThat(ts.fromAi()).isEqualTo(7);
        assertThat(ts.fromWorkflow()).isEqualTo(5);
    }

    @Test
    void getPlatformStats_wishStats() {
        stubAllEmpty();
        Wish completed = Wish.builder().status(WishStatus.COMPLETED).build();
        Wish failed = Wish.builder().status(WishStatus.FAILED).build();
        Wish cancelled = Wish.builder().status(WishStatus.CANCELLED).build();
        Wish inProgress = Wish.builder().status(WishStatus.EXECUTING).build();
        Wish received = Wish.builder().status(WishStatus.RECEIVED).build();
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(
                List.of(completed, failed, cancelled, inProgress, received));

        PlatformStatsResponse result = service.getPlatformStats();

        PlatformStatsResponse.WishStats ws = result.wishStats();
        assertThat(ws.total()).isEqualTo(5);
        assertThat(ws.completed()).isEqualTo(1);
        assertThat(ws.failed()).isEqualTo(2); // FAILED + CANCELLED
        assertThat(ws.inProgress()).isEqualTo(2); // EXECUTING + RECEIVED
    }

    @Test
    void getPlatformStats_assistantStats_noSessions() {
        stubAllEmpty();
        when(assistantSessionRepository.count()).thenReturn(0L);

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.assistantStats().totalSessions()).isZero();
        assertThat(result.assistantStats().avgMessagesPerSession()).isZero();
    }

    @Test
    void getPlatformStats_assistantStats_withSessions() {
        stubAllEmpty();
        when(assistantSessionRepository.count()).thenReturn(3L);
        when(assistantSessionRepository.findAll()).thenReturn(List.of(
                AssistantSession.builder().messageCount(10).build(),
                AssistantSession.builder().messageCount(20).build(),
                AssistantSession.builder().messageCount(30).build()));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.assistantStats().totalSessions()).isEqualTo(3);
        assertThat(result.assistantStats().avgMessagesPerSession()).isEqualTo(20.0);
    }

    @Test
    void getPlatformStats_workflowPoolSize_countsJsonFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("wf1.json"), "{}");
        Files.writeString(tempDir.resolve("wf2.json"), "{}");
        Files.writeString(tempDir.resolve("readme.txt"), "ignore me");
        ReflectionTestUtils.setField(service, "workflowPoolPath", tempDir.toString());
        stubAllEmpty();

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.workflowPoolSize()).isEqualTo(2);
    }

    @Test
    void getPlatformStats_workflowPoolSize_nonExistentDir() {
        ReflectionTestUtils.setField(service, "workflowPoolPath", "/tmp/nonexistent-dir-xyz");
        stubAllEmpty();

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.workflowPoolSize()).isZero();
    }

    @Test
    void getPlatformStats_topSkillsByUsage_limitsToTen() {
        stubAllEmpty();
        Workflow wf = Workflow.builder().id("wf1").name("test").build();
        List<Skill> skills = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            skills.add(Skill.builder().id("s" + i).name("Skill" + i)
                    .category(SkillCategory.CODE).workflows(List.of(wf)).build());
        }
        when(skillRepository.findAll()).thenReturn(skills);

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.topSkillsByUsage()).hasSize(10);
    }

    @Test
    void getPlatformStats_topSkillsByUsage_sortedByWorkflowCountDesc() {
        stubAllEmpty();
        Workflow wf1 = Workflow.builder().id("wf1").name("a").build();
        Workflow wf2 = Workflow.builder().id("wf2").name("b").build();
        Workflow wf3 = Workflow.builder().id("wf3").name("c").build();
        Skill few = Skill.builder().id("s1").name("Few")
                .category(SkillCategory.CODE).workflows(List.of(wf1)).build();
        Skill many = Skill.builder().id("s2").name("Many")
                .category(SkillCategory.DATA).workflows(List.of(wf1, wf2, wf3)).build();
        when(skillRepository.findAll()).thenReturn(List.of(few, many));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.topSkillsByUsage()).hasSize(2);
        assertThat(result.topSkillsByUsage().get(0).skillName()).isEqualTo("Many");
        assertThat(result.topSkillsByUsage().get(0).workflowCount()).isEqualTo(3);
        assertThat(result.topSkillsByUsage().get(1).skillName()).isEqualTo("Few");
    }

    @Test
    void getPlatformStats_topSkillsByUsage_nullWorkflowsHandled() {
        stubAllEmpty();
        Skill nullWf = Skill.builder().id("s1").name("NullWf")
                .category(SkillCategory.CODE).workflows(null).build();
        when(skillRepository.findAll()).thenReturn(List.of(nullWf));

        PlatformStatsResponse result = service.getPlatformStats();

        assertThat(result.topSkillsByUsage()).isEmpty();
    }

    /**
     * Stubs all repository calls to return empty/zero so individual tests
     * only need to override the repos they care about.
     */
    private void stubAllEmpty() {
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
        when(projectRepository.findByArchivedAtIsNotNull()).thenReturn(List.of());
        when(workflowRepository.count()).thenReturn(0L);
        when(skillRepository.findAll()).thenReturn(List.of());
        when(templateRepository.count()).thenReturn(0L);
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of());
        when(scheduleRepository.count()).thenReturn(0L);
        when(scheduleRepository.findByEnabledTrue()).thenReturn(List.of());
        when(webhookRepository.count()).thenReturn(0L);
        when(webhookRepository.countByEnabledTrue()).thenReturn(0L);
        when(chainRepository.count()).thenReturn(0L);
        when(chainRunRepository.count()).thenReturn(0L);
        when(chainRunRepository.countByStatus(RunStatus.COMPLETED)).thenReturn(0L);
        when(chainRunRepository.countByStatus(RunStatus.FAILED)).thenReturn(0L);
        when(todoTriggerRepository.findByEnabledTrue()).thenReturn(List.of());
        when(completionTriggerRepository.countByEnabledTrue()).thenReturn(0L);
        when(memoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());
        when(todoRepository.countGroupedByStatus()).thenReturn(new TodoCountResponse(0, 0, 0, 0, 0));
        when(todoRepository.countBySource(Todo.Source.USER)).thenReturn(0L);
        when(todoRepository.countBySource(Todo.Source.AI)).thenReturn(0L);
        when(todoRepository.countBySource(Todo.Source.WORKFLOW)).thenReturn(0L);
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(assistantSessionRepository.count()).thenReturn(0L);
    }
}

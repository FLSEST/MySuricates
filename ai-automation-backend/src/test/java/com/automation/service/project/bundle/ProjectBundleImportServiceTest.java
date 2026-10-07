package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundleImportRequest.TargetProject;
import com.automation.dto.bundle.BundleImportResult;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Skill;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.SkillRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.testsupport.FakeTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Request checks, path mapping and the transaction boundary of the import — the writer itself is mocked. */
class ProjectBundleImportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final BundleImportWriter writer = mock(BundleImportWriter.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final ActivityLogService activityLog = mock(ActivityLogService.class);
    private final BundleImportResult result = new BundleImportResult("p-new",
            new BundleImportResult.Created(2, 1, 1), List.of(new BundleImportResult.Skipped("workflow", "x", "exists")),
            List.of(), List.of());
    private final boolean[] writtenInTransaction = new boolean[1];
    private ProjectBundleImportService service;

    @BeforeEach
    void setUp() {
        service = service(ModuleRegistry.allEnabled());
        when(skills.findAll()).thenReturn(List.of(Skill.builder().id("s-1").name("git-tools").build(),
                Skill.builder().id("s-2").name("git-tools").build()));
        when(writer.write(any(), any(), anyBoolean(), anyMap(), anyBoolean(), anySet())).thenAnswer(inv -> {
            writtenInTransaction[0] = FakeTransactions.active();
            return result;
        });
        when(activityLog.info(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            assertThat(FakeTransactions.active()).as("activity entry after the commit").isFalse();
            return null;
        });
    }

    private ProjectBundleImportService service(ModuleRegistry modules) {
        return new ProjectBundleImportService(new BundleRequestReader(MAPPER), writer, skills, modules, activityLog,
                FakeTransactions.manager());
    }

    private static Map<String, Object> bundle() {
        return Map.of("format", "mysuricates-project-bundle", "formatVersion", 1,
                "project", Map.of("name", "Shop"),
                "workflows", List.of(Map.of("key", "tests", "definition", Map.of("name", "Tests",
                        "instructions", "cd @@path:1@@/src", "activatedSkillNames", List.of("missing")))),
                "chains", List.of(Map.of("key", "all", "name", "All", "entries", List.of(Map.of("workflowKey", "tests")))),
                "paths", List.of(Map.of("token", "@@path:1@@", "original", "/old", "occurrences", 1, "hint", "h")));
    }

    private static String body(Map<String, Object> project, Map<String, String> mappings, String onConflict)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("bundle", bundle());
        body.put("project", project);
        body.put("pathMappings", mappings);
        body.put("onConflict", onConflict);
        return MAPPER.writeValueAsString(body);
    }

    private static Map<String, Object> newProject() {
        return Map.of("mode", " NEW ", "name", "Shop", "path", "/new");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesTheMappedBundleInOneTransactionAndLogsAfterwards() throws Exception {
        BundleImportResult answer = service.importBundle(body(newProject(), Map.of("@@path:1@@", "/new/"), null));

        assertThat(answer).isSameAs(result);
        ArgumentCaptor<ProjectBundle> bundle = ArgumentCaptor.forClass(ProjectBundle.class);
        ArgumentCaptor<Map<String, String>> skillIds = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Set<String>> warnings = ArgumentCaptor.forClass(Set.class);
        verify(writer).write(bundle.capture(), eq(new TargetProject("new", "Shop", "/new", null)), eq(false),
                skillIds.capture(), eq(true), warnings.capture());
        assertThat(bundle.getValue().workflows().get(0).definition().instructions()).isEqualTo("cd /new/src");
        assertThat(skillIds.getValue()).containsExactly(Map.entry("git-tools", "s-1"));
        assertThat(warnings.getValue()).containsExactly(BundleWarnings.missingSkill("missing"));
        assertThat(writtenInTransaction[0]).isTrue();
        verify(activityLog).info(eq(LogCategory.SYSTEM),
                eq("Project imported from bundle: 2 workflows, 1 chains, 1 schedules (disabled), 1 skipped"),
                eq("ProjectBundleImportService"), eq("p-new"));
    }

    @Test
    void renameAndExistingTargetArePassedOnNormalized() throws Exception {
        service.importBundle(body(Map.of("mode", "Existing", "projectId", "p-1"), Map.of("@@path:1@@", "/x"),
                " RENAME "));

        verify(writer).write(any(), eq(new TargetProject("existing", null, null, "p-1")), eq(true), anyMap(),
                eq(true), anySet());
    }

    @Test
    void chainsModuleOff_isPassedToTheWriterAndWarned() throws Exception {
        service = service(ModuleRegistry.of(Map.of(), Map.of()));

        service.importBundle(body(newProject(), Map.of("@@path:1@@", "/x"), "skip"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> warnings = ArgumentCaptor.forClass(Set.class);
        verify(writer).write(any(), any(), eq(false), anyMap(), eq(false), warnings.capture());
        assertThat(warnings.getValue()).contains(BundleWarnings.CHAINS_OFF);
    }

    @Test
    void rejectsAMissingOrUnknownMode() {
        assertThatThrownBy(() -> service.importBundle(body(null, Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("project.mode is required ('new' or 'existing')");
        assertThatThrownBy(() -> service.importBundle(body(Map.of("name", "x"), Map.of(), null)))
                .hasMessage("project.mode is required ('new' or 'existing')");
        assertThatThrownBy(() -> service.importBundle(body(Map.of("mode", "merge"), Map.of(), null)))
                .hasMessage("project.mode must be 'new' or 'existing', got 'merge'");
        verifyNoInteractions(writer, activityLog);
    }

    @Test
    void existingModeNeedsAProjectId() {
        assertThatThrownBy(() -> service.importBundle(body(Map.of("mode", "existing", "projectId", " "),
                Map.of(), null)))
                .hasMessage("project.projectId is required for mode 'existing'");
        assertThatThrownBy(() -> service.importBundle(body(Map.of("mode", "existing"), Map.of(), null)))
                .hasMessage("project.projectId is required for mode 'existing'");
    }

    @Test
    void rejectsAnUnknownOnConflict() {
        assertThatThrownBy(() -> service.importBundle(body(newProject(), Map.of("@@path:1@@", "/x"), "overwrite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("onConflict must be 'skip' or 'rename', got 'overwrite'");
        verifyNoInteractions(writer);
    }

    @Test
    void invalidBundleOrMissingPathMappingWritesNothing() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("bundle", Map.of("format", "other", "formatVersion", 1));
        body.put("project", newProject());

        assertThatThrownBy(() -> service.importBundle(MAPPER.writeValueAsString(body)))
                .hasMessageContaining("format must be");
        assertThatThrownBy(() -> service.importBundle(body(newProject(), Map.of(), null)))
                .hasMessage("Missing path mapping for: @@path:1@@ (/old)");
        assertThatThrownBy(() -> service.importBundle(""))
                .hasMessage("Request body is missing");
        verifyNoInteractions(writer);
        verify(activityLog, never()).info(any(), anyString(), anyString(), anyString());
    }
}

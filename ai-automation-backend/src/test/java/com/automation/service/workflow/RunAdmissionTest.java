package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.BlockDefinition;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorMessage;
import com.automation.exception.HardeningException;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunAdmissionTest {

    @Mock private WorkflowVersionService versionService;
    @Mock private ActivityLogService activityLog;
    @Mock private WorkflowSkillPins skillPins;

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();

    /** Signer without a key: seals stay unsigned, exactly as before signing existed. */
    static WorkflowSealSigner unsignedSigner() {
        return new WorkflowSealSigner(java.nio.file.Path.of("/nonexistent/seal.key"), name -> null);
    }

    private static Workflow workflow() {
        return Workflow.builder().id("wf-1").name("Deploy")
                .blocks(new ArrayList<>(List.of(BlockDefinition.builder().type("shell").config(new java.util.HashMap<>(Map.of("command", "make"))).build())))
                .activatedSkills(new ArrayList<>())
                .build();
    }

    @Test
    void recordsHashAndMatchingVersionWhenProvenanceIsOn() {
        Workflow wf = workflow();
        String hash = hasher.hash(wf);
        when(versionService.findVersionIdByHash("wf-1", hash)).thenReturn(Optional.of("ver-7"));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isEqualTo(hash);
        assertThat(ticket.definitionVersionId()).isEqualTo("ver-7");
    }

    @Test
    void hashWithoutVersionWhenNoRowMatches() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket.definitionHash()).hasSize(64);
        assertThat(ticket.definitionVersionId()).isNull();
    }

    @Test
    void recordsNothingWhenProvenanceIsOff() {
        RunAdmission admission = new RunAdmission(
                ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer());

        RunAdmission.Ticket ticket = admission.admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket).isEqualTo(new RunAdmission.Ticket(null, null, false, null)); // seal state is recorded regardless
        verifyNoInteractions(versionService);
    }

    @Test
    void unhashableDefinitionDoesNotBlockTheRun() {
        Workflow broken = Workflow.builder().id("wf-x").blocks(new ArrayList<>()).activatedSkills(null).build();
        when(versionService.findVersionIdByHash(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(broken, TriggerSource.WEBHOOK, Map.of());

        assertThat(ticket.definitionHash()).isNull();
    }

    @Test
    void sealedWorkflowWithMatchingHashRuns() {
        Workflow wf = workflow();
        wf.setSealed(true);
        wf.setSealedHash(hasher.hash(wf));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isNull(); // provenance off — but the seal was still verified
        verifyNoInteractions(activityLog);
    }

    @Test
    void tamperedSealedWorkflowIsRefusedAndLoggedEvenWithSwitchesOff() {
        Workflow wf = workflow();
        wf.setSealed(true);
        wf.setSealedHash("0".repeat(64));
        RunAdmission admission = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer());

        assertThatThrownBy(() -> admission.admit(wf, TriggerSource.WEBHOOK, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WORKFLOW_TAMPERED))
                .hasMessage("Sealed workflow 'Deploy' was not started: its definition no longer matches the seal")
                // 2b73ff48: the reason travels as messageKey + params, the UI translates it
                .satisfies(e -> {
                    assertThat(((HardeningException) e).getErrorMessage()).isEqualTo(ErrorMessage.SEAL_DEFINITION_CHANGED);
                    assertThat(((HardeningException) e).getParams()).isEqualTo(Map.of("name", "Deploy"));
                });
        verify(activityLog).logRefusal(eq(LogLevel.ERROR), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                argThat(meta -> "WEBHOOK".equals(meta.get("triggerSource")) && hasher.hash(wf).equals(meta.get("actualHash"))
                        // 2b73ff48: the seal status translates the latest refusal from these
                        && "WORKFLOW_TAMPERED".equals(meta.get("errorCode"))
                        && "SEAL_DEFINITION_CHANGED".equals(meta.get("messageKey"))
                        && Map.of("name", "Deploy").equals(meta.get("params"))),
                eq("wf-1"));
    }

    private RunAdmission policyOn() {
        return new RunAdmission(ModuleRegistry.of(Map.of(),
                Map.of("sealing", "true", "require-sealed-for-unattended", "true")), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer());
    }

    @Test
    void unattendedRunOfUnsealedWorkflowIsRefusedWhenThePolicyIsOn() {
        assertThatThrownBy(() -> policyOn().admit(workflow(), TriggerSource.SCHEDULE, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.UNATTENDED_REQUIRES_SEAL))
                .satisfies(e -> assertThat(((HardeningException) e).getParams()).containsEntry("origin", "SCHEDULE").containsKey("name"))
                .hasMessageContaining("SCHEDULE");
        verify(activityLog).logRefusal(eq(LogLevel.WARN), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                argThat(meta -> "SCHEDULE".equals(meta.get("effectiveSource"))
                        && "UNATTENDED_REQUIRES_SEAL".equals(meta.get("errorCode")) && !meta.containsKey("messageKey")
                        && Map.of("name", "Deploy", "origin", "SCHEDULE").equals(meta.get("params"))), eq("wf-1"));
        verifyNoInteractions(versionService);
    }

    @Test
    void attendedRunsAndSealedWorkflowsPassThePolicy() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());
        RunAdmission admission = policyOn();

        assertThat(admission.admit(workflow(), TriggerSource.MANUAL, Map.of()).definitionHash()).hasSize(64);
        Workflow sealed = workflow();
        sealed.setSealed(true);
        sealed.setSealedHash(hasher.hash(sealed));
        assertThat(admission.admit(sealed, TriggerSource.WEBHOOK, Map.of()).definitionHash()).isEqualTo(sealed.getSealedHash());
        verifyNoInteractions(activityLog);
    }

    @Test
    void policyOffLetsUnattendedRunsOfUnsealedWorkflowsThrough() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(workflow(), TriggerSource.TODO_TRIGGER, Map.of());

        verifyNoInteractions(activityLog);
    }

    @Test
    void chainAndSubWorkflowRunsInheritTheirOrigin_unknownCountsAsUnattended() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());
        RunAdmission admission = policyOn();

        admission.admit(workflow(), TriggerSource.CHAIN, Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL"));
        assertThatThrownBy(() -> admission.admit(workflow(), TriggerSource.SUB_WORKFLOW,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "WEBHOOK"))).isInstanceOf(HardeningException.class);
        assertThatThrownBy(() -> admission.admit(workflow(), TriggerSource.CHAIN, Map.of()))
                .isInstanceOf(HardeningException.class).hasMessageContaining("unknown");
        assertThatThrownBy(() -> admission.admit(workflow(), TriggerSource.CHAIN,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SUB_WORKFLOW"))).isInstanceOf(HardeningException.class);
    }

    @Test
    void unverifiableSealIsRefused() {
        Workflow broken = Workflow.builder().id("wf-x").name("Broken").sealed(true).sealedHash("abc").build();
        broken.setMetadata(null);
        broken.setBlocks(null);
        Workflow spy = org.mockito.Mockito.spy(broken);
        org.mockito.Mockito.doThrow(new IllegalStateException("lazy")).when(spy).getActivatedSkills();

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(spy, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("could not be verified");
    }

    private Workflow sealedWithPins(Map<String, String> pins) {
        Workflow sealed = workflow();
        sealed.setSealed(true);
        sealed.setSealedHash(hasher.hash(sealed));
        sealed.setSealedSkillHashes(WorkflowSkillPins.toJson(pins));
        return sealed;
    }

    @Test
    void sealedWorkflowWhoseSkillsStillMatchRuns() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));

        new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(sealed, TriggerSource.SCHEDULE, Map.of());

        verifyNoInteractions(activityLog);
    }

    @Test
    void sealedWorkflowWithAChangedSkillIsRefused() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1", "skill-b", "h2"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1", "skill-b", "tampered"));

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WORKFLOW_TAMPERED))
                .hasMessageContaining("skill-b").hasMessageNotContaining("skill-a")
                .satisfies(e -> {
                    assertThat(((HardeningException) e).getErrorMessage()).isEqualTo(ErrorMessage.SEAL_SKILLS_CHANGED);
                    assertThat(((HardeningException) e).getParams()).containsEntry("skills", "skill-b").containsKey("name");
                });
        verify(activityLog).logRefusal(eq(LogLevel.ERROR), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                any(), eq("wf-1"));
        verifyNoInteractions(versionService);
    }

    @Test
    void deletedSkillOfASealedWorkflowIsRefused() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", WorkflowSkillPins.MISSING));

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class).hasMessageContaining("skill-a");
    }

    @Test
    void unverifiableSkillPinsAreRefused() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class).hasMessageContaining("skills could not be verified");
    }

    @Test
    void sealWithoutSkillPinsStillRunsButDoesNotCheckSkills() {
        Workflow legacy = workflow();
        legacy.setSealed(true);
        legacy.setSealedHash(hasher.hash(legacy));

        new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(legacy, TriggerSource.MANUAL, Map.of());

        verifyNoInteractions(skillPins, activityLog);
    }

    @Test
    void recordsTheSealStateAndTheVerifiedSkillPinsOnTheTicket() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher,
                new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(sealed, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.sealed()).isTrue();
        assertThat(ticket.sealedSkillPins()).isEqualTo("{\"skill-a\":\"h1\"}");
        assertThat(ticket.definitionHash()).isEqualTo(sealed.getSealedHash());
    }

    @Test
    void unsealedRunIsRecordedAsNotSealed() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher,
                new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())), versionService, activityLog, new WorkflowAnalyzer())
                .admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket.sealed()).isFalse();
        assertThat(ticket.sealedSkillPins()).isNull();
    }

    private static WorkflowSealSigner signerWith(java.nio.file.Path keyFile) {
        WorkflowSealSigner signer = new WorkflowSealSigner(keyFile, name -> null);
        signer.init();
        return signer;
    }

    private RunAdmission admissionWith(WorkflowSealSigner signer, ModuleRegistry registry) {
        return new RunAdmission(registry, hasher, new WorkflowSealVerifier(hasher, skillPins, signer, registry),
                versionService, activityLog, new WorkflowAnalyzer());
    }

    @Test
    void sealWithAValidSignatureRuns(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        WorkflowSealSigner signer = signerWith(dir.resolve("seal.key"));
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        sealed.setSealedSignature(signer.sign(sealed.getId(), sealed.getSealedHash(), Map.of("skill-a", "h1")));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));

        admissionWith(signer, ModuleRegistry.of(Map.of(), Map.of("provenance", "false")))
                .admit(sealed, TriggerSource.SCHEDULE, Map.of());

        verifyNoInteractions(activityLog);
    }

    @Test
    void forgedSealIsRefusedEvenWhenHashAndSkillsMatch(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // what a direct database edit looks like: definition and sealed_hash rewritten together
        WorkflowSealSigner signer = signerWith(dir.resolve("seal.key"));
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        sealed.setSealedSignature(signerWith(dir.resolve("other.key"))
                .sign(sealed.getId(), sealed.getSealedHash(), Map.of("skill-a", "h1")));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));

        assertThatThrownBy(() -> admissionWith(signer, ModuleRegistry.allEnabled())
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("signature does not match");
    }

    @Test
    void unsignedSealRunsUntilRequireSignedSealIsOn() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));

        admissionWith(unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of("provenance", "false")))
                .admit(sealed, TriggerSource.MANUAL, Map.of());

        assertThatThrownBy(() -> admissionWith(unsignedSigner(),
                ModuleRegistry.of(Map.of(), Map.of("sealing", "true", "require-signed-seal", "true")))
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("no signature");
    }

    // --- e7e93fba: unattended runs of definitions that reach into the platform itself ---

    private RunAdmission defaults() {
        return new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher,
                new WorkflowSealVerifier(hasher, skillPins, unsignedSigner(), ModuleRegistry.of(Map.of(), Map.of())),
                versionService, activityLog, new WorkflowAnalyzer());
    }

    private static Workflow withBlocks(BlockDefinition... blocks) {
        return Workflow.builder().id("wf-1").name("Deploy").blocks(new ArrayList<>(List.of(blocks)))
                .activatedSkills(new ArrayList<>()).build();
    }

    private static BlockDefinition shell(String name, String command) {
        return BlockDefinition.builder().type("shell").name(name).config(new java.util.HashMap<>(Map.of("command", command))).build();
    }

    private static Workflow selfApiCaller() {
        return withBlocks(shell("ok", "make"),
                shell("fetch", "curl -s -H \"X-API-Key: $(cat ~/.mysuricates/api-key)\" http://localhost:9090/api/v1/todos"));
    }

    @Test
    void unattendedRunWithSelfApiCallIsRefusedAndLoggedAsSecurity() {
        assertThatThrownBy(() -> defaults().admit(selfApiCaller(), TriggerSource.SCHEDULE, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.UNATTENDED_PLATFORM_ACCESS))
                .satisfies(e -> assertThat(((HardeningException) e).getParams()).isEqualTo(
                        Map.of("name", "Deploy", "origin", "SCHEDULE", "rule", "self-api-call", "block", "2")))
                .hasMessageContaining("self-api-call in block 2");
        verify(activityLog).logRefusal(eq(LogLevel.WARN), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                argThat(meta -> "SCHEDULE".equals(meta.get("effectiveSource"))
                        && "UNATTENDED_PLATFORM_ACCESS".equals(meta.get("errorCode"))
                        && meta.get("findings") instanceof List<?> findings && findings.size() == 1),
                eq("wf-1"));
    }

    @Test
    void everyUnattendedSourceIsRefused_directDbAccessToo() {
        Workflow db = withBlocks(shell("db", "sqlite3 data/automation.db 'SELECT value FROM app_settings'"));
        for (TriggerSource source : List.of(TriggerSource.WEBHOOK, TriggerSource.TODO_TRIGGER,
                TriggerSource.COMPLETION_TRIGGER, TriggerSource.AUTO_REPAIR, TriggerSource.AUTO_RESUME, TriggerSource.WISH)) {
            assertThatThrownBy(() -> defaults().admit(db, source, Map.of())).as(source.name())
                    .isInstanceOf(HardeningException.class)
                    .satisfies(e -> assertThat(((HardeningException) e).getParams()).containsEntry("rule", "direct-db-access"));
        }
        // a chain or sub-workflow inherits its origin — unattended or unknown counts as unattended
        assertThatThrownBy(() -> defaults().admit(db, TriggerSource.CHAIN,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE"))).isInstanceOf(HardeningException.class);
        assertThatThrownBy(() -> defaults().admit(db, TriggerSource.SUB_WORKFLOW, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getParams()).containsEntry("origin", "unknown"));
    }

    @Test
    void aPersonMayStillStartIt() {
        for (TriggerSource source : List.of(TriggerSource.MANUAL, TriggerSource.RETRY, TriggerSource.RESUME,
                TriggerSource.TODO_RESOLUTION, TriggerSource.ASSISTANT)) {
            assertThat(defaults().admit(selfApiCaller(), source, Map.of())).as(source.name()).isNotNull();
        }
        assertThat(defaults().admit(selfApiCaller(), TriggerSource.CHAIN,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL"))).isNotNull();
        verifyNoInteractions(activityLog);
    }

    @Test
    void mediumFindingsAndOtherRulesDoNotStopUnattendedRuns() {
        Workflow prompt = withBlocks(BlockDefinition.builder().type("simple").name("p").config(new java.util.HashMap<>(Map.of(
                "command", "Hole die TODOs mit curl http://localhost:9090/api/v1/todos"))).build());
        Workflow probe = withBlocks(shell("health", "curl -sf http://localhost:9090/api/v1/system/health"));
        Workflow projectDb = withBlocks(shell("own", "sqlite3 app/local.db '.tables'"));

        for (Workflow wf : List.of(prompt, probe, projectDb, workflow())) {
            assertThat(defaults().admit(wf, TriggerSource.SCHEDULE, Map.of())).isNotNull();
        }
        verifyNoInteractions(activityLog);
    }

    // TODO 9525820a: the process sandbox spares only runs a person started, directly or as their origin
    @Test
    void attended_onlyForRunsAPersonStarted() {
        assertThat(RunAdmission.attended(TriggerSource.MANUAL, Map.of())).isTrue();
        assertThat(RunAdmission.attended(TriggerSource.SCHEDULE, Map.of())).isFalse();
        assertThat(RunAdmission.attended(TriggerSource.SUB_WORKFLOW,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL"))).isTrue();
        assertThat(RunAdmission.attended(TriggerSource.SUB_WORKFLOW,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE"))).isFalse();
        assertThat(RunAdmission.attended(TriggerSource.SUB_WORKFLOW, Map.of())).as("unknown origin").isFalse();
    }
}

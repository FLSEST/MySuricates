package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.workflow.WorkflowSealVerifier.Problem;
import com.automation.service.workflow.WorkflowSealVerifier.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSealVerifierTest {

    private static final String HASH = "a".repeat(64);
    private static final Map<String, String> PINS = Map.of("skill-a", "h1", "skill-b", "h2");
    private static final String PINS_JSON = WorkflowSkillPins.toJson(PINS);

    @Mock private WorkflowDefinitionHasher hasher;
    @Mock private WorkflowSkillPins skillPins;
    @Mock private WorkflowSealSigner signer;
    @Mock private ModuleRegistry modules;

    private WorkflowSealVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WorkflowSealVerifier(hasher, skillPins, signer, modules);
    }

    private static Workflow sealed(String sealedHash, String skillHashesJson, String signature) {
        Workflow wf = Workflow.builder().id("wf-1").name("Deploy").build();
        wf.setSealed(true);
        wf.setSealedHash(sealedHash);
        wf.setSealedSkillHashes(skillHashesJson);
        wf.setSealedSignature(signature);
        return wf;
    }

    // --- NONE ---

    @Test
    void unsignedSealWithoutSkillPinsHoldsWhileSignaturesAreOptional() {
        Workflow wf = sealed(HASH, null, null);
        when(hasher.hash(wf)).thenReturn(HASH);
        when(modules.isEnabled(ModuleCatalog.REQUIRE_SIGNED_SEAL)).thenReturn(false);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.NONE);
        assertThat(verdict.intact()).isTrue();
        assertThat(verdict.actualHash()).isEqualTo(HASH);
        assertThat(verdict.skillsPinned()).isFalse();
        assertThat(verdict.pins()).isNull();
        assertThat(verdict.changedSkills()).isEmpty();
        assertThat(verdict.detail()).isNull();
        assertThat(verdict.signed()).isFalse();
        verifyNoInteractions(skillPins, signer);
    }

    @Test
    void signedSealWithMatchingSkillPinsHoldsAndReturnsThePins() {
        Workflow wf = sealed(HASH, PINS_JSON, "sig");
        when(hasher.hash(wf)).thenReturn(HASH);
        when(skillPins.pins(wf)).thenReturn(PINS);
        when(signer.verify("wf-1", HASH, PINS, "sig")).thenReturn(true);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.NONE);
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.pins()).isEqualTo(PINS);
        assertThat(verdict.signed()).isTrue();
        assertThat(verdict.detail()).isNull();
        // A present, valid signature settles it — require-signed-seal is not even asked.
        verifyNoInteractions(modules);
    }

    @Test
    void signedSealWithoutSkillPinsIsVerifiedWithoutPins() {
        Workflow wf = sealed(HASH, null, "sig");
        when(hasher.hash(wf)).thenReturn(HASH);
        when(signer.verify("wf-1", HASH, null, "sig")).thenReturn(true);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.NONE);
        assertThat(verdict.signed()).isTrue();
        assertThat(verdict.pins()).isNull();
        verifyNoInteractions(skillPins);
    }

    // --- DEFINITION_CHANGED ---

    @Test
    void changedDefinitionBreaksTheSealBeforeSkillsOrSignatureAreChecked() {
        Workflow wf = sealed(HASH, PINS_JSON, "sig");
        when(hasher.hash(wf)).thenReturn("b".repeat(64));

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.DEFINITION_CHANGED);
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.actualHash()).isEqualTo("b".repeat(64));
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.pins()).isNull();
        assertThat(verdict.signed()).isFalse();
        assertThat(verdict.detail()).contains("no longer matches the seal");
        verifyNoInteractions(skillPins, signer, modules);
    }

    // --- SKILLS_CHANGED ---

    @Test
    void changedAddedOrRemovedSkillBreaksTheSealAndNamesTheSkills() {
        Workflow wf = sealed(HASH, PINS_JSON, "sig");
        when(hasher.hash(wf)).thenReturn(HASH);
        // skill-a changed, skill-b removed, skill-c added
        when(skillPins.pins(wf)).thenReturn(Map.of("skill-a", "h1-new", "skill-c", "h3"));

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.SKILLS_CHANGED);
        assertThat(verdict.changedSkills()).containsExactly("skill-a", "skill-b", "skill-c");
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.pins()).isNull();
        assertThat(verdict.detail()).contains("skill-a, skill-b, skill-c").contains("seal again");
        verifyNoInteractions(signer, modules);
    }

    // --- SIGNATURE_INVALID ---

    @Test
    void signatureThatDoesNotMatchBreaksTheSealEvenWithRequireSignedSealOff() {
        Workflow wf = sealed(HASH, PINS_JSON, "forged");
        when(hasher.hash(wf)).thenReturn(HASH);
        when(skillPins.pins(wf)).thenReturn(PINS);
        when(signer.verify("wf-1", HASH, PINS, "forged")).thenReturn(false);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.SIGNATURE_INVALID);
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.actualHash()).isEqualTo(HASH);
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.pins()).isNull();
        assertThat(verdict.signed()).isTrue();
        assertThat(verdict.detail()).contains("signature does not match");
        verifyNoInteractions(modules);
    }

    // --- SIGNATURE_MISSING ---

    @Test
    void missingSignatureBreaksTheSealOnlyWhenRequireSignedSealIsOn() {
        Workflow wf = sealed(HASH, PINS_JSON, null);
        when(hasher.hash(wf)).thenReturn(HASH);
        when(skillPins.pins(wf)).thenReturn(PINS);
        when(modules.isEnabled(ModuleCatalog.REQUIRE_SIGNED_SEAL)).thenReturn(true);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.SIGNATURE_MISSING);
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.pins()).isNull();
        assertThat(verdict.signed()).isFalse();
        assertThat(verdict.detail()).contains("require-signed-seal");
        verify(signer, never()).verify(anyString(), anyString(), any(), anyString());
    }

    @Test
    void missingSignatureIsAcceptedWhileRequireSignedSealIsOff() {
        Workflow wf = sealed(HASH, PINS_JSON, null);
        when(hasher.hash(wf)).thenReturn(HASH);
        when(skillPins.pins(wf)).thenReturn(PINS);
        when(modules.isEnabled(ModuleCatalog.REQUIRE_SIGNED_SEAL)).thenReturn(false);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.NONE);
        assertThat(verdict.pins()).isEqualTo(PINS);
        assertThat(verdict.signed()).isFalse();
    }

    // --- UNVERIFIABLE (fail closed) ---

    @Test
    void definitionThatCannotBeHashedIsUnverifiable() {
        Workflow wf = sealed(HASH, PINS_JSON, "sig");
        when(hasher.hash(wf)).thenThrow(new IllegalStateException("blocks not serialisable"));

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.UNVERIFIABLE);
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.actualHash()).isNull();
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.signed()).isFalse();
        assertThat(verdict.detail()).contains("definition could not be verified").contains("blocks not serialisable");
        verifyNoInteractions(skillPins, signer, modules);
    }

    @Test
    void skillsThatCannotBeHashedAreUnverifiable() {
        Workflow wf = sealed(HASH, PINS_JSON, "sig");
        when(hasher.hash(wf)).thenReturn(HASH);
        when(skillPins.pins(wf)).thenThrow(new IllegalStateException("skill store down"));

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.UNVERIFIABLE);
        assertThat(verdict.actualHash()).isEqualTo(HASH);
        assertThat(verdict.skillsPinned()).isTrue();
        assertThat(verdict.detail()).contains("skills could not be verified").contains("skill store down");
        verifyNoInteractions(signer, modules);
    }

    @Test
    void unreadableStoredSkillPinsAreUnverifiable() {
        Workflow wf = sealed(HASH, "{not json", "sig");
        when(hasher.hash(wf)).thenReturn(HASH);

        Verdict verdict = verifier.verify(wf);

        assertThat(verdict.problem()).isEqualTo(Problem.UNVERIFIABLE);
        assertThat(verdict.detail()).contains("skills could not be verified");
        verifyNoInteractions(skillPins, signer, modules);
    }
}

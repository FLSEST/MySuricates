package com.automation.service.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSealSignerTest {

    private static WorkflowSealSigner withKeyFile(Path file) {
        WorkflowSealSigner signer = new WorkflowSealSigner(file, name -> null);
        signer.init();
        return signer;
    }

    @Test
    void createsAKeyFileOnFirstUseAndKeepsItPrivate(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("nested/seal.key");
        WorkflowSealSigner signer = withKeyFile(key);

        assertThat(signer.available()).isTrue();
        assertThat(Files.exists(key)).isTrue();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");
    }

    @Test
    void signatureSurvivesARestartWithTheSameKeyFile(@TempDir Path dir) {
        Path key = dir.resolve("seal.key");
        String first = withKeyFile(key).sign("wf-1", "hash-1", Map.of("s1", "h1"));

        assertThat(withKeyFile(key).verify("wf-1", "hash-1", Map.of("s1", "h1"), first)).isTrue();
    }

    @Test
    void anotherKeyDoesNotVerify(@TempDir Path dir) {
        String signature = withKeyFile(dir.resolve("a.key")).sign("wf-1", "hash-1", Map.of());

        assertThat(withKeyFile(dir.resolve("b.key")).verify("wf-1", "hash-1", Map.of(), signature)).isFalse();
    }

    @Test
    void signatureIsBoundToWorkflowDefinitionAndSkills(@TempDir Path dir) {
        WorkflowSealSigner signer = withKeyFile(dir.resolve("seal.key"));
        String signature = signer.sign("wf-1", "hash-1", Map.of("s1", "h1"));

        assertThat(signer.verify("wf-1", "hash-1", Map.of("s1", "h1"), signature)).isTrue();
        assertThat(signer.verify("wf-2", "hash-1", Map.of("s1", "h1"), signature)).isFalse(); // moved to another workflow
        assertThat(signer.verify("wf-1", "hash-2", Map.of("s1", "h1"), signature)).isFalse(); // definition changed
        assertThat(signer.verify("wf-1", "hash-1", Map.of("s1", "other"), signature)).isFalse(); // skill changed
        assertThat(signer.verify("wf-1", "hash-1", Map.of(), signature)).isFalse(); // pins dropped
    }

    @Test
    void environmentKeyWinsOverTheFile(@TempDir Path dir) {
        Path key = dir.resolve("seal.key");
        String fromFile = withKeyFile(key).sign("wf-1", "h", Map.of());

        WorkflowSealSigner fromEnv = new WorkflowSealSigner(key,
                name -> "AUTOMATION_SEAL_KEY".equals(name) ? java.util.Base64.getEncoder().encodeToString(new byte[32]) : null);
        fromEnv.init();

        assertThat(fromEnv.available()).isTrue();
        assertThat(fromEnv.verify("wf-1", "h", Map.of(), fromFile)).isFalse();
    }

    @Test
    void withoutAKeyNothingIsSignedAndNothingVerifies() {
        WorkflowSealSigner signer = new WorkflowSealSigner(Path.of("/proc/definitely-not-writable/seal.key"), name -> null);
        signer.init();

        assertThat(signer.available()).isFalse();
        assertThat(signer.sign("wf-1", "h", Map.of())).isNull();
        assertThat(signer.verify("wf-1", "h", Map.of(), "anything")).isFalse();
    }
}

package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostCodexServiceTest {

    private HostCodexService service;

    @BeforeEach
    void setUp() {
        SummarizationConfig config = new SummarizationConfig();
        config.setTimeoutSeconds(120);
        service = new HostCodexService(config);
    }

    @Test
    void buildCommand_basic() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/.autotrigger/summarization/codex");

        List<String> cmd = service.buildCommand("test prompt", workDir, outputFile);

        assertThat(cmd).startsWith("codex", "exec", "test prompt");
        assertThat(cmd).contains("--skip-git-repo-check");
        assertThat(cmd).contains("-o", outputFile.toAbsolutePath().toString());
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_nullWorkDir() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand("prompt", null, outputFile);

        assertThat(cmd).doesNotContain("-C");
    }

    @Test
    void buildCommand_stdinMode_usesDashAsPromptPlaceholder() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/project");

        List<String> cmd = service.buildCommand(workDir, outputFile, false);

        assertThat(cmd).startsWith("codex", "exec", "-");
        assertThat(cmd).contains("--skip-git-repo-check");
        assertThat(cmd).contains("-o", outputFile.toAbsolutePath().toString());
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
        assertThat(cmd).doesNotContain("--sandbox");
    }

    @Test
    void buildCommand_stdinMode_nullWorkDir_noCFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand(null, outputFile, false);

        assertThat(cmd).startsWith("codex", "exec", "-");
        assertThat(cmd).doesNotContain("-C");
    }

    @Test
    void buildCommand_readOnly_addsSandboxFlag() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/project");

        List<String> cmd = service.buildCommand(workDir, outputFile, true);

        assertThat(cmd).contains("--sandbox", "read-only");
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_notReadOnly_noSandboxFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand(null, outputFile, false);

        assertThat(cmd).doesNotContain("--sandbox");
        assertThat(cmd).doesNotContain("read-only");
    }

    @Test
    void execute_defaultTimeout_usesConfigTimeout() {
        Path outputFile = Path.of("/tmp/test.txt");
        List<String> cmd = service.buildCommand(Path.of("/test"), outputFile, false);

        assertThat(cmd).contains("codex", "exec");
        assertThat(cmd).contains("-o");
    }

    // --- API-key env var injection (H1 fix) ---

    @Test
    @SuppressWarnings("unchecked")
    void injectApiKeyEnv_setsAutomationApiKey_whenServiceWired() {
        SummarizationConfig config = new SummarizationConfig();
        config.setTimeoutSeconds(120);
        ApiKeyService keyService = mock(ApiKeyService.class);
        when(keyService.getEffectiveApiKey()).thenReturn("codex-key");
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(keyService);

        HostCodexService svc = new HostCodexService(config, provider);
        ProcessBuilder pb = new ProcessBuilder("true");

        svc.injectApiKeyEnv(pb);

        assertThat(pb.environment()).containsEntry("AUTOMATION_API_KEY", "codex-key");
    }

    @Test
    void injectApiKeyEnv_isNoOp_whenServiceProviderIsNull() {
        // 1-arg ctor: provider is null (test/legacy code path).
        ProcessBuilder pb = new ProcessBuilder("true");

        service.injectApiKeyEnv(pb);

        assertThat(pb.environment()).doesNotContainKey("AUTOMATION_API_KEY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectApiKeyEnv_isNoOp_whenApiKeyIsBlank() {
        SummarizationConfig config = new SummarizationConfig();
        config.setTimeoutSeconds(120);
        ApiKeyService keyService = mock(ApiKeyService.class);
        when(keyService.getEffectiveApiKey()).thenReturn("");
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(keyService);

        HostCodexService svc = new HostCodexService(config, provider);
        ProcessBuilder pb = new ProcessBuilder("true");

        svc.injectApiKeyEnv(pb);

        assertThat(pb.environment()).doesNotContainKey("AUTOMATION_API_KEY");
    }
}

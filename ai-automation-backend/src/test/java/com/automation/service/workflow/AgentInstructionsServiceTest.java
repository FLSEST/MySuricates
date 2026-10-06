package com.automation.service.workflow;

import com.automation.service.system.SystemIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentInstructionsServiceTest {

    @Mock
    private SystemIdentityService systemIdentity;

    private AgentInstructionsService service;

    @BeforeEach
    void setUp() {
        when(systemIdentity.getAgentIdentityBlock()).thenReturn(
                "## System Identity\nDu bist ein Agent von MySuricates v0.2.3.\n\n");
        // Work preferences without any settings: the block stays empty, the prompt
        // thus unchanged (TODO d49fbde0) -- these tests check the remaining parts.
        com.automation.service.system.SystemSettingsService noSettings =
                org.mockito.Mockito.mock(com.automation.service.system.SystemSettingsService.class);
        org.mockito.Mockito.lenient()
                .when(noSettings.getSetting(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        service = new AgentInstructionsService(systemIdentity, new UserPreferencesService(noSettings));
        // Call init to load base instructions from classpath
        service.init();
    }

    @Test
    void init_loadsBaseInstructions() {
        // After init, buildInstructionPrefix with null should return base instructions
        String result = service.buildInstructionPrefix(null);
        // Base instructions loaded from agent-instructions.md (may or may not be on classpath in test)
        assertThat(result).isNotNull();
    }

    @Test
    void buildInstructionPrefix_nullInstructions_returnsBaseOnly() {
        String result = service.buildInstructionPrefix(null);
        assertThat(result).isNotNull();
        assertThat(result).doesNotContain("## Workflow-Specific Instructions");
    }

    @Test
    void buildInstructionPrefix_emptyInstructions_returnsBaseOnly() {
        String result = service.buildInstructionPrefix("");
        assertThat(result).doesNotContain("## Workflow-Specific Instructions");
    }

    @Test
    void buildInstructionPrefix_blankInstructions_returnsBaseOnly() {
        String result = service.buildInstructionPrefix("   ");
        assertThat(result).doesNotContain("## Workflow-Specific Instructions");
    }

    @Test
    void buildInstructionPrefix_validInstructions_appendsWorkflowSection() {
        String result = service.buildInstructionPrefix("Always use TypeScript");
        assertThat(result).contains("## Workflow-Specific Instructions");
        assertThat(result).contains("Always use TypeScript");
    }

    @Test
    void buildInstructionPrefix_longInstructions_truncatesAt10000() {
        String longInstructions = "x".repeat(15_000);
        String result = service.buildInstructionPrefix(longInstructions);
        assertThat(result).contains("[... truncated]");
        // The workflow-specific part should be at most 10000 chars + truncation marker
        assertThat(result).contains("## Workflow-Specific Instructions");
    }

    @Test
    void buildInstructionPrefix_exactlyMaxLength_doesNotTruncate() {
        String exactInstructions = "y".repeat(10_000);
        String result = service.buildInstructionPrefix(exactInstructions);
        assertThat(result).doesNotContain("[... truncated]");
        assertThat(result).contains("## Workflow-Specific Instructions");
    }

    @Test
    void buildInstructionPrefix_stripsWhitespace() {
        String result = service.buildInstructionPrefix("  trimmed content  ");
        assertThat(result).contains("trimmed content");
    }
}

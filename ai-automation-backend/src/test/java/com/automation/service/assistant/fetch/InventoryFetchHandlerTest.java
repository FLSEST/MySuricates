package com.automation.service.assistant.fetch;

import com.automation.entity.ChainEntry;
import com.automation.entity.IntegrationConnection;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.WorkflowChain;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.IntegrationVerification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryFetchHandlerTest {

    @Mock private WishRepository wishRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private IntegrationRegistry integrationRegistry;
    @Mock private IntegrationConnectionRepository integrationConnectionRepository;
    @Mock private WorkflowChainRepository workflowChainRepository;
    @Mock private WorkflowRepository workflowRepository;
    @InjectMocks private InventoryFetchHandler handler;

    @Test
    void wish_shortIdIsNotOverrun_andIterationsShown() {
        Wish wish = Wish.builder().id("w1").originalText("Baue X").status(WishStatus.RECEIVED)
                .iterationCount(2).maxIterations(5).build();
        when(wishRepository.findById("w1")).thenReturn(Optional.of(wish));

        assertThat(handler.formatWishDetail("w1"))
                .isEqualTo("**Wish w1…** — [RECEIVED] Baue X\nIterations: 2/5\n");
    }

    @Test
    void everyDetail_repositoryFailure_returnsEmptyString() {
        when(wishRepository.findById("x")).thenThrow(new RuntimeException("db"));
        when(skillRepository.findByIdWithCapabilities("x")).thenThrow(new RuntimeException("db"));
        when(integrationRegistry.hasProvider("x")).thenThrow(new RuntimeException("db"));
        when(workflowChainRepository.findById("x")).thenThrow(new RuntimeException("db"));

        assertThat(handler.formatWishDetail("x")).isEmpty();
        assertThat(handler.formatSkillDetail("x")).isEmpty();
        assertThat(handler.formatIntegrationDetail("x")).isEmpty();
        assertThat(handler.formatChainDetail("x")).isEmpty();
    }

    @Test
    void skill_withoutOptionalFieldsOrCapabilities_isCompact() {
        Skill skill = Skill.builder().id("s1").name("Bare").category(SkillCategory.RESEARCH)
                .description(" ").capabilities(new ArrayList<>()).build();
        when(skillRepository.findByIdWithCapabilities("s1")).thenReturn(Optional.of(skill));

        assertThat(handler.formatSkillDetail("s1")).isEqualTo("**Skill: Bare** (id: s1)\nCategory: RESEARCH\n");
    }

    @Test
    void integration_nonOAuthProvider_withoutActions_andBlankAccountInfo() {
        IntegrationProvider provider = mock(IntegrationProvider.class);
        when(provider.getDisplayName()).thenReturn("Taskwarrior");
        when(provider.getProviderId()).thenReturn("taskwarrior");
        when(provider.getDescription()).thenReturn(null);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.getVerification()).thenReturn(IntegrationVerification.VERIFIED);
        when(provider.getVerifiedAgainst()).thenReturn("Taskwarrior-CLI auf dem Host");
        when(provider.getAvailableActions()).thenReturn(null);
        when(integrationRegistry.hasProvider("taskwarrior")).thenReturn(true);
        when(integrationRegistry.getProvider("taskwarrior")).thenReturn(provider);
        when(integrationConnectionRepository.findByProviderId("taskwarrior")).thenReturn(Optional.of(
                IntegrationConnection.builder().providerId("taskwarrior")
                        .status(IntegrationConnection.ConnectionStatus.CONNECTED).accountInfo(" ").build()));

        assertThat(handler.formatIntegrationDetail("taskwarrior")).isEqualTo(
                "**Integration: Taskwarrior** (id: taskwarrior)\nStatus: CONNECTED\nOAuth flow required: no (API key/token)\n"
                        + "Verification: checked against the real service (Taskwarrior-CLI auf dem Host)\n");
    }

    @Test
    void integration_simulatedProvider_saysItWasNotCheckedAgainstTheRealService() {
        IntegrationProvider provider = mock(IntegrationProvider.class);
        when(provider.getDisplayName()).thenReturn("Gitea");
        when(provider.getProviderId()).thenReturn("gitea");
        when(provider.getVerification()).thenReturn(IntegrationVerification.SIMULATED);
        when(integrationRegistry.hasProvider("gitea")).thenReturn(true);
        when(integrationRegistry.getProvider("gitea")).thenReturn(provider);
        when(integrationConnectionRepository.findByProviderId("gitea")).thenReturn(Optional.empty());

        assertThat(handler.formatIntegrationDetail("gitea"))
                .contains("Verification: simulated only — not yet checked against the real service\n");
    }

    @Test
    void integration_actionWithoutSchemaOrScopes_listsOnlyName() {
        IntegrationProvider provider = mock(IntegrationProvider.class);
        when(provider.getDisplayName()).thenReturn("X");
        when(provider.getProviderId()).thenReturn("x");
        when(provider.getAvailableActions()).thenReturn(List.of(new IntegrationAction("ping", null, Map.of())));
        when(integrationRegistry.hasProvider("x")).thenReturn(true);
        when(integrationRegistry.getProvider("x")).thenReturn(provider);
        when(integrationConnectionRepository.findByProviderId("x")).thenReturn(Optional.empty());

        assertThat(handler.formatIntegrationDetail("x")).endsWith("Available actions (1):\n  - ping\n");
    }

    @Test
    void chain_entryInputsAreListedByKey_andStopOnFailureNo() {
        ChainEntry entry = ChainEntry.builder().workflowId("wf-a").label(" ")
                .inputContext(Map.of("branch", "main")).build();
        WorkflowChain chain = WorkflowChain.builder().id("c1").name("Pipe").stopOnFailure(false)
                .entries(new ArrayList<>(List.of(entry))).build();
        when(workflowChainRepository.findById("c1")).thenReturn(Optional.of(chain));
        when(workflowRepository.findById("wf-a")).thenReturn(Optional.empty());

        assertThat(handler.formatChainDetail("c1")).isEqualTo(
                "**Chain: Pipe** (id: c1)\nStop on failure: no\n\nWorkflows in Chain (1):\n"
                        + "  1. wf-a (id: wf-a)\n    inputs: [branch]\n");
    }
}

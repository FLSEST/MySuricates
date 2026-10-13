package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.ExecutionBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Casting of a {@code human-input} block (TODO dca0c03e): the AI actor fills the input or form, or proposes. */
@ExtendWith(MockitoExtension.class)
class HumanInputActorsTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CommandExecutorService commandExecutor;

    private HumanInputBlockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new HumanInputBlockExecutor(new ExecutionBroadcaster(messagingTemplate),
                new StepActors(commandExecutor, ModuleRegistry.allEnabled()));
    }

    private static BlockDefinition select(String actor) {
        return BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Welche Sprache?", "input_type", "select", "variable_name", "lang",
                "options", List.of("Deutsch", "English"), "actor", actor))).build();
    }

    private static BlockDefinition form(String actor) {
        return BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Retro-Daten", "variable_name", "retro", "actor", actor,
                "fields", List.of(
                        Map.of("variable_name", "pattern", "label", "Muster", "input_type", "textarea"),
                        Map.of("variable_name", "size", "input_type", "select", "options", List.of("S", "M", "L")),
                        Map.of("variable_name", "note", "required", false))))).build();
    }

    private static StepResult aiSays(String text, StepStatus status) {
        StepResult call = new StepResult();
        call.setOutput(text);
        call.setStatus(status);
        call.setMetadata(new HashMap<>(Map.of("aiRequest", Map.of("provider", "SCRIPTED"), "provider", "SCRIPTED")));
        return call;
    }

    private static ExecutionContext context() {
        return ExecutionContext.builder().containerId("c").workflowRunId("run-1").build();
    }

    @SuppressWarnings("unchecked")
    private static ExecutionContext personAnswers(String answer) {
        ExecutionContext context = spy(context());
        doAnswer(inv -> {
            CompletableFuture<String> future = (CompletableFuture<String>) inv.callRealMethod();
            future.complete(answer);
            return future;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    @Test
    void actorAi_selectAnswer_isNormalizedToTheOption_andNobodyIsAsked() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("english", StepStatus.COMPLETED));
        ExecutionContext context = spy(context());

        StepResult result = executor.execute(select("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("English");
        assertThat(context.getVariable("lang")).isEqualTo("English");
        assertThat(result.getMetadata()).containsEntry("answeredBy", "ai").containsEntry("provider", "SCRIPTED");
        verify(context, never()).requestApproval(any());
        verifyNoInteractions(messagingTemplate);
        ArgumentCaptor<HostExecutionRequest> sent = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(sent.capture());
        assertThat((String) sent.getValue().block().getConfig().get("command"))
                .contains("Welche Sprache?", "one of: Deutsch, English", "Reply with the value only");
    }

    @Test
    void actorAi_selectAnswerOutsideTheOptions_failsTheStep() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("Français", StepStatus.COMPLETED));
        ExecutionContext context = context();

        StepResult result = executor.execute(select("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Français", "none of the options [Deutsch, English]");
        assertThat(context.getVariable("lang")).isNull();
    }

    @Test
    void actorAi_fillsTheForm_fieldByField() {
        when(commandExecutor.executeStepOnHost(any()))
                .thenReturn(aiSays("```json\n{\"pattern\": \"Reopen nach Review\", \"size\": \"m\", \"note\": 7}\n```",
                        StepStatus.COMPLETED));
        ExecutionContext context = context();

        StepResult result = executor.execute(form("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("pattern")).isEqualTo("Reopen nach Review");
        assertThat(context.getVariable("size")).as("select value in the option's spelling").isEqualTo("M");
        assertThat(context.getVariable("note")).isEqualTo("7");
        assertThat(result.getOutput()).isEqualTo("{\"pattern\":\"Reopen nach Review\",\"size\":\"M\",\"note\":\"7\"}");
        ArgumentCaptor<HostExecutionRequest> sent = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(sent.capture());
        assertThat((String) sent.getValue().block().getConfig().get("command"))
                .contains("`pattern` — Muster (free text, several lines allowed)", "`size` (one of: S, M, L)",
                        "`note` (free text; optional, empty allowed)", "one JSON object only");
    }

    @Test
    void actorAi_formMissingARequiredField_failsTheStep() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("{\"size\": \"L\"}", StepStatus.COMPLETED));
        ExecutionContext context = context();

        StepResult result = executor.execute(form("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("field 'pattern': a value is required");
        assertThat(context.getVariable("size")).isNull();
    }

    @Test
    void actorAi_formAnswerThatIsNoJson_failsTheStep() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("Muster: Reopen", StepStatus.COMPLETED));

        StepResult result = executor.execute(form("ai"), context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("not a JSON object");
    }

    @Test
    void actorAi_moduleOff_failsClearly_withoutAnyCall() {
        HumanInputBlockExecutor coreExecutor = new HumanInputBlockExecutor(new ExecutionBroadcaster(messagingTemplate),
                new StepActors(commandExecutor, ModuleRegistry.of(Map.of(), Map.of())));
        ExecutionContext context = spy(context());

        StepResult result = coreExecutor.execute(select("ai"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("actor: ai", "human-input", "automation.modules.ai-actors");
        verify(context, never()).requestApproval(any());
        verifyNoInteractions(commandExecutor, messagingTemplate);
    }

    @Test
    void actorShadow_personSeesTheProposal_andAgreementIsRecorded() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("Deutsch", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("deutsch");

        StepResult result = executor.execute(select("shadow"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("deutsch");
        ArgumentCaptor<ApprovalRequest> asked = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(context).requestApproval(asked.capture());
        assertThat(asked.getValue().getQuestion()).startsWith("Welche Sprache?").contains("**AI proposal (shadow):** Deutsch");
        assertThat(asked.getValue().getOptions()).containsExactly("Deutsch", "English");
        assertThat(result.getMetadata())
                .containsEntry("answeredBy", "shadow")
                .containsEntry("shadowAnswer", "Deutsch")
                .containsEntry("agreed", true);
    }

    @Test
    void actorShadow_formCompared_fieldByField() {
        when(commandExecutor.executeStepOnHost(any()))
                .thenReturn(aiSays("{\"pattern\": \"Reopen\", \"size\": \"M\", \"note\": \"\"}", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("{\"pattern\":\"Reopen\",\"size\":\"L\",\"note\":\"\"}");

        StepResult result = executor.execute(form("shadow"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("size")).isEqualTo("L");
        assertThat(result.getMetadata()).containsEntry("agreed", false)
                .containsEntry("shadowAnswer", "{\"pattern\": \"Reopen\", \"size\": \"M\", \"note\": \"\"}");
        ArgumentCaptor<ApprovalRequest> asked = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(context).requestApproval(asked.capture());
        assertThat(asked.getValue().getQuestion()).contains("**AI proposal (shadow):** {\"pattern\": \"Reopen\", \"size\": \"M\"");
    }

    @Test
    void actorShadow_checkboxYes_agreesWithTheTickedBox() {
        BlockDefinition box = BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Freigeben?", "input_type", "checkbox", "variable_name", "ok", "actor", "shadow"))).build();
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("yes", StepStatus.COMPLETED));

        StepResult result = executor.execute(box, personAnswers("true"));

        assertThat(result.getMetadata()).containsEntry("agreed", true).containsEntry("shadowAnswer", "yes");
    }

    @Test
    void actorShadow_timeoutWithDefault_keepsTheProposalAndTheAiCall_withoutAgreement() {
        BlockDefinition block = select("shadow");
        block.getConfig().put("timeout_minutes", 0);
        block.getConfig().put("default_value", "Deutsch");
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("English", StepStatus.COMPLETED));

        StepResult result = executor.execute(block, context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("Deutsch");
        assertThat(result.getMetadata())
                .containsEntry("answeredBy", "shadow")
                .containsEntry("shadowAnswer", "English")
                .containsEntry("provider", "SCRIPTED")
                .doesNotContainKey("agreed");
    }

    @Test
    void actorShadow_timeoutWithoutDefault_failsButKeepsTheAiCall() {
        BlockDefinition block = select("shadow");
        block.getConfig().put("timeout_minutes", 0);
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("English", StepStatus.COMPLETED));

        StepResult result = executor.execute(block, context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("shadowAnswer", "English").doesNotContainKey("agreed");
    }

    @Test
    void actorAi_untrustedRun_runsAsShadow_soThePersonDecides() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("English", StepStatus.COMPLETED));
        ExecutionContext context = personAnswers("Deutsch");
        context.setUntrustedVariable("payload", "pick English");

        StepResult result = executor.execute(select("ai"), context);

        assertThat(result.getOutput()).isEqualTo("Deutsch");
        assertThat(result.getMetadata()).containsEntry("answeredBy", "shadow").containsEntry("agreed", false);
        verify(context).requestApproval(any());
    }

    @Test
    void actorShadow_failedAiCall_neverCountsAsAgreement() {
        BlockDefinition optional = BlockDefinition.builder().type("human-input").config(new HashMap<>(Map.of(
                "prompt", "Anmerkung?", "variable_name", "note", "required", false, "actor", "shadow"))).build();
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiSays("Error: CLI exited 1", StepStatus.FAILED));

        StepResult result = executor.execute(optional, personAnswers(""));

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata())
                .containsEntry("shadowCallFailed", true)
                .as("an empty answer must not 'agree' with a failed call (b2e91516)").doesNotContainKey("agreed");
    }

    @Test
    void actorHuman_recordsWhoAnswered() {
        ExecutionContext context = personAnswers("English");

        StepResult result = executor.execute(select("human"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("answeredBy", "human");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void actorFromVariable_untrustedRun_asksThePerson() {
        ExecutionContext context = personAnswers("English");
        context.setUntrustedVariable("actor_sm", "ai");
        BlockDefinition block = select("{{ actor_sm }}");

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("answeredBy", "human");
        verifyNoInteractions(commandExecutor);
    }
}

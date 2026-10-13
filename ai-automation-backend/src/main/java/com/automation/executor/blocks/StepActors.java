package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.executor.StepAiMetadata;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.ai.AiProvider;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Casting of a decision step (TODO dca0c03e): who answers an {@code approval} or {@code human-input} block.
 *
 * <p>The config key {@value #ACTOR_KEY} names the actor — {@code human} (default, the block behaves as it
 * always did), {@code ai} (one AI call in the role answers the step) or {@code shadow} (the AI answers first,
 * the person is still asked and sees the proposal; both answers go into the step metadata). The key may be a
 * placeholder ({@code {{ actor_po }}}), so a run variable casts the roles; in a run with untrusted input a
 * placeholder is never resolved — exactly like {@code mode}/{@code auto_answer}, otherwise a webhook payload
 * could cast itself as the approver. {@value #ACTOR_PROMPT_KEY} carries the role instructions. A literal
 * {@code ai} in such a run becomes {@code shadow}: the question or form is resolved with that input, and an
 * AI deciding alone could be talked into the answer by the payload — the person decides instead.
 *
 * <p>The feature is the module {@link ModuleCatalog#AI_ACTORS} (off in the {@code core} preset). With the
 * module off, {@code ai} and {@code shadow} are refused — the step fails with a clear message and the analyzer
 * reports {@code actor-module-disabled} — never silently answered by a person.
 *
 * <p>The AI call goes through {@link CommandExecutorService#executeStepOnHost} like a {@code simple} block:
 * provider, model and effort via {@code BlockAiSettings} (block &gt; workflow &gt; system default), the
 * provider throttles, and the usual {@code aiRequest} step metadata. The call gets no tools — a decision needs
 * none, and a role that could edit files would be an agent, not an actor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepActors {

    public static final String ACTOR_KEY = "actor";
    public static final String ACTOR_PROMPT_KEY = "actor_prompt";
    /** Step metadata: who answered — {@code human}, {@code ai} or {@code shadow}. */
    public static final String ANSWERED_BY = "answeredBy";
    /** Step metadata of a shadow step: the AI's proposal. */
    public static final String SHADOW_ANSWER = "shadowAnswer";
    /**
     * Step metadata of a shadow step: whether the person's answer equals the AI's proposal. Absent when no
     * person answered (timeout, cancel, default value) — that is neither agreement nor disagreement.
     */
    public static final String AGREED = "agreed";
    /**
     * Step metadata of a shadow step whose AI call failed: there was no proposal to agree with, so
     * {@value #AGREED} is left out (java.review b2e91516).
     */
    public static final String SHADOW_CALL_FAILED = "shadowCallFailed";

    private static final String DEFAULT_ROLE = "You answer this step in place of the person who would normally "
            + "decide it. Decide on the basis given below; do not ask questions back.";

    public enum Actor {
        HUMAN, AI, SHADOW;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The outcome of one AI call in the role: the answer text (stripped) and the call's step metadata. */
    public record AiAnswer(String text, boolean failed, Map<String, Object> metadata) {}

    private final CommandExecutorService commandExecutor;
    private final ModuleRegistry modules;

    /**
     * The actor of a block: {@code human} unless the config says {@code ai} or {@code shadow}. A placeholder
     * is resolved only in a run without untrusted input and only to a known value; anything else is
     * {@code human} ({@link ApprovalBlockExecutor#resolveSwitch}). In a run with untrusted input {@code ai}
     * becomes {@code shadow}, so a person decides a question that the input helped to word.
     */
    public static Actor resolve(Map<String, Object> config, ExecutionContext context) {
        String raw = config == null ? null : ApprovalBlockExecutor.resolveSwitch(config.get(ACTOR_KEY), context);
        if (raw == null) return Actor.HUMAN;
        Actor actor = switch (raw.toLowerCase(Locale.ROOT)) {
            case "ai" -> Actor.AI;
            case "shadow" -> Actor.SHADOW;
            default -> Actor.HUMAN;
        };
        if (actor == Actor.AI && context.hasUntrustedInput()) {
            log.warn("actor: ai runs as shadow - the run carries untrusted input, a person decides");
            return Actor.SHADOW;
        }
        return actor;
    }

    /** True when the literal config value asks for an AI actor — the analyzer's view, no run needed. */
    public static boolean asksForAi(Object rawActor) {
        if (!(rawActor instanceof String value)) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "ai".equals(v) || "shadow".equals(v);
    }

    public boolean enabled() {
        return modules.isEnabled(ModuleCatalog.AI_ACTORS);
    }

    /** The step output when the module is off and the block asked for an AI actor. */
    public static String disabledMessage(String blockType, Actor actor) {
        return ModuleCatalog.AI_ACTORS.disabledMessage("actor: " + actor.key() + " on block type '" + blockType + "'");
    }

    /**
     * One AI call in the role. {@code task} is the resolved question or form plus the answer format; the role
     * instructions ({@value #ACTOR_PROMPT_KEY}, resolved) go in front of it.
     */
    public AiAnswer answer(BlockDefinition block, ExecutionContext context, int stepIndex, String task) {
        Map<String, Object> config = block.getConfig() == null ? Map.of() : block.getConfig();
        String role = config.get(ACTOR_PROMPT_KEY) instanceof String s && !s.isBlank()
                ? context.resolveTemplate(s) : DEFAULT_ROLE;
        String prompt = role.strip() + "\n\n" + task.strip();
        Integer timeout = config.get("timeout_seconds") instanceof Number n ? n.intValue() : null;
        String projectPath = context.getProjectPath();
        if (projectPath == null || projectPath.isBlank()) {
            projectPath = System.getProperty("java.io.tmpdir");
        }
        StepResult call = commandExecutor.executeStepOnHost(HostExecutionRequest.builder()
                .projectPath(projectPath)
                .block(BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", prompt))).build())
                .stepIndex(stepIndex)
                .systemPrompt(context.getInstructionPrefix())
                .model(context.modelFor(block))
                .timeoutSeconds(timeout)
                .providerId(context.providerFor(block))
                .effort(context.effortFor(block))
                .allowedTools(AiProvider.NO_TOOLS)
                .build());
        String text = call.getOutput() == null ? "" : call.getOutput().strip();
        boolean failed = call.getStatus() != StepStatus.COMPLETED;
        if (failed) {
            log.warn("AI actor call failed for step {}: {}", stepIndex, text);
        }
        return new AiAnswer(text, failed, call.getMetadata() == null ? Map.of() : call.getMetadata());
    }

    /** Step metadata of an AI-answered step: {@code answeredBy} plus the call's AI keys. */
    public static Map<String, Object> aiMetadata(Actor actor, AiAnswer answer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ANSWERED_BY, actor.key());
        if (answer != null) {
            StepResult carrier = new StepResult();
            StepAiMetadata.carryAi(carrier, answer.metadata());
            if (carrier.getMetadata() != null) metadata.putAll(carrier.getMetadata());
        }
        return metadata;
    }

    /**
     * Step metadata of a shadow step: the AI keys, the proposal and whether the person agreed — unless the AI
     * call failed: without a proposal there is nothing to agree with, so {@value #AGREED} is left out.
     */
    public static Map<String, Object> shadowMetadata(AiAnswer proposal, String proposalText, boolean agreed) {
        Map<String, Object> metadata = unansweredShadowMetadata(proposal, proposalText);
        if (proposal == null || !proposal.failed()) {
            metadata.put(AGREED, agreed);
        }
        return metadata;
    }

    /**
     * Step metadata of a shadow step no person answered (timeout, cancel, default value): the AI call was made
     * and paid for, so its keys and the proposal stay; {@value #AGREED} is left out.
     */
    public static Map<String, Object> unansweredShadowMetadata(AiAnswer proposal, String proposalText) {
        Map<String, Object> metadata = aiMetadata(Actor.SHADOW, proposal);
        metadata.put(SHADOW_ANSWER, proposalText);
        if (proposal != null && proposal.failed()) {
            metadata.put(SHADOW_CALL_FAILED, true);
        }
        return metadata;
    }

    /** The proposal as the person sees it, appended to the question or prompt (Markdown). */
    public static String withProposal(String question, String proposal) {
        String shown = proposal == null || proposal.isBlank() ? "(no answer)" : proposal.strip();
        return question + "\n\n---\n**AI proposal (shadow):** " + (shown.contains("\n") ? "\n\n```\n" + shown + "\n```" : shown);
    }

    /** A step output naming the failed AI call — the step fails, nobody is asked instead. */
    public static String failedCallOutput(AiAnswer answer) {
        return "AI actor call failed: " + (answer.text().isBlank() ? "(no output)" : answer.text());
    }
}

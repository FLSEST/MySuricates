package com.automation.integration.scenario;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderCapabilities;
import com.automation.service.ai.AiResponseMetadata;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.ProviderSetup;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * An {@link AiProvider} for integration tests: answers from a script instead of starting a CLI, and
 * records every call (TODO fdf79a86). GitHub has neither {@code claude} nor {@code codex} nor
 * {@code pi}, but almost every real failure of this platform sat in the deterministic plumbing
 * around the model — prompt building, placeholders, timeouts, model choice before the call; marker
 * parsing, gate aggregation, auto-TODOs after it. With this provider in place of the CLI, a real
 * workflow definition runs through the real executor and both sides of the seam can be checked.
 *
 * <p>Rules are tried in order: the first whose {@code match} occurs in the prompt answers; a rule
 * without {@code match} answers anything. No matching rule means exit code 1 and a message naming
 * the prompt — a scenario never passes on a silent default answer.
 */
public class ScriptedAiProvider implements AiProvider {

    /** One scripted answer. {@code match} null or blank = answers every prompt. */
    public record Rule(String match, String respond, int exitCode) {
        boolean answers(String prompt) {
            return match == null || match.isBlank() || (prompt != null && prompt.contains(match));
        }
    }

    /** What the platform handed to the provider: the "before" half of the seam. */
    public record Call(String phase, String prompt, String systemPrompt, String model,
                       Integer timeoutSeconds, String effort, String workingDirectory) {}

    private final String id;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public ScriptedAiProvider(String id) {
        this.id = id;
    }

    /** Replaces the script and forgets earlier calls. */
    public void script(List<Rule> newRules) {
        rules.clear();
        rules.addAll(newRules);
        calls.clear();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public String getId() {
        return id;
    }

    /** Everything supported, so model, effort and system prompt reach the call as they would for Claude. */
    @Override
    public AiProviderCapabilities getCapabilities() {
        return AiProviderCapabilities.full();
    }

    @Override
    public ProviderSetup setup() {
        return new ProviderSetup("scripted", "(test only)", "(test only)", List.of(), List.of());
    }

    /** Like the real providers, a rule with exit code ≠ 0 is no answer but a failed call (9cc6c615). */
    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        calls.add(new Call("reason", request.prompt(), request.systemPrompt(), request.model(),
                request.timeoutSeconds(), request.effort(), request.workingDirectory()));
        Rule rule = answer(request.prompt());
        if (rule.exitCode() != 0) {
            throw new IOException("Scripted reasoning failed with exit code " + rule.exitCode() + ": " + rule.respond());
        }
        return new ReasoningResult(rule.respond(), AiResponseMetadata.estimated(request.prompt(), rule.respond()));
    }

    @Override
    public ActionResult executeAction(ActionRequest request) {
        calls.add(new Call("act", request.prompt(), request.systemPrompt(), request.model(),
                request.timeoutSeconds(), request.effort(), request.workingDirectory()));
        Rule rule = answer(request.prompt());
        return new ActionResult(rule.respond(), rule.exitCode(), AiResponseMetadata.estimated(request.prompt(), rule.respond()));
    }

    @Override
    public String buildConversationPrompt(String systemPrompt, List<HostClaudeService.Turn> history) {
        return (systemPrompt == null ? "" : systemPrompt + "\n\n")
                + history.stream().map(Object::toString).collect(Collectors.joining("\n"));
    }

    private Rule answer(String prompt) {
        return rules.stream().filter(rule -> rule.answers(prompt)).findFirst()
                .orElseGet(() -> new Rule(null, "ScriptedAiProvider " + id + ": no rule answers this prompt:\n" + prompt, 1));
    }
}

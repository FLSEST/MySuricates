package com.automation.service.dashboard;

import com.automation.config.DashboardReportAssistConfig;
import com.automation.dto.response.DashboardReportAssistResponse;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.catalog.ModelCatalogService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor's AI proposal for a {@code dashboard-report} block (TODO f4231dc9): reads the tail of a
 * workflow's latest run and asks one tool-less AI call to turn it into the closed contract.
 *
 * <p><b>No {@code @Transactional} anywhere here.</b> The AI call takes seconds to minutes; with
 * pool-size = 1 a transaction around it would hold the only SQLite connection for that whole time.
 * The two reads are single projection queries that run in their own implicit transaction, are done
 * before the call starts, and never load {@code StepResult.input} or {@code context_delta}.
 *
 * <p><b>Nothing unchecked leaves this class.</b> The answer goes through the existing, hardened
 * {@link DashboardWidgetResultParser#parse} — the very path built for "AI text → contract", down to
 * the rule that two markers in one answer are ambiguous and rejected, so a marker quoted out of the
 * source cannot pose as the proposal. Refusals carry a fixed message: no CLI error text, no run
 * output, no fragment of the answer.
 *
 * <p><b>"No AI connected ⇒ nothing is offered" asks the host, not the bean list.</b> Whether the
 * provider is registered as a Spring bean says nothing: {@code ClaudeAiProvider} is an
 * unconditional component, so that condition is true on an instance without any Claude CLI. The
 * question is asked of {@link ModelCatalogService} instead — the path the settings UI already uses
 * to find out what a provider offers here. For {@code CLAUDE} it answers from a PATH lookup, so no
 * process is started on this request path and the single SQLite connection is never touched; the
 * catalog's TTL cache absorbs repeated asks, and a negative answer is re-checked within a minute.
 * An installed but unauthenticated CLI cannot be told apart without spending a call — that case
 * fails later, with the fixed "the AI did not answer" message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardReportAssistService {

    /** The report a workflow writes stands at its end, so the tail is what matters. */
    static final int MAX_SOURCE_CHARS = 12_000;

    /** Enough context for a summary, few enough rows to keep the single connection free. */
    static final int MAX_STEPS = 5;

    /** The assist calls without tools, and only the Claude CLI can do that today (like compress-ai). */
    private static final String PROVIDER = "CLAUDE";

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepRepository;
    private final CommandExecutorService commandExecutor;
    private final ModelCatalogService modelCatalog;
    private final DashboardReportAssistConfig config;

    public DashboardReportAssistResponse propose(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (!config.isEnabled()) {
            throw new IllegalStateException("Der KI-Vorschlag für Dashboard-Berichte ist abgeschaltet "
                    + "(Einstellungen → Ausführung).");
        }
        if (!modelCatalog.getCatalog(PROVIDER, false).available()) {
            // The reason can name a path or a CLI message — the caller only learns that it is off.
            throw new IllegalStateException("Der KI-Vorschlag braucht den Anbieter " + PROVIDER
                    + "; auf diesem Host ist dessen CLI nicht verfügbar.");
        }
        if (workflowRepository.findIdAndNameByIdIn(List.of(workflowId)).isEmpty()) {
            throw new ResourceNotFoundException("Workflow", workflowId);
        }
        Object[] run = runRepository.findLatestRunPerWorkflow(List.of(workflowId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Dieser Workflow hat noch keinen Run."));
        String runId = (String) run[1];
        String source = sourceOf(runId);

        String answer = ask(source);
        DashboardWidgetResultParser.ParseResult parsed = DashboardWidgetResultParser.parse(answer);
        if (parsed.state() != ResultState.VALID) {
            log.info("dashboard-report assist: the answer for run {} did not match the contract ({})",
                    runId, parsed.state());
            throw new IllegalStateException("Der Vorschlag der KI passte nicht zum Vertrag. "
                    + "Die Felder lassen sich von Hand ausfüllen.");
        }
        Result proposal = parsed.result();
        return new DashboardReportAssistResponse(runId, (Instant) run[4], PROVIDER, proposal);
    }

    /** Last steps of the run, in step order, capped from the end. */
    private String sourceOf(String runId) {
        List<String> newestFirst = stepRepository.findOutputsByRunIdNewestFirst(runId, PageRequest.of(0, MAX_STEPS));
        StringBuilder joined = new StringBuilder();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            String output = newestFirst.get(i);
            if (output != null && !output.isBlank()) {
                joined.append(output.strip()).append("\n\n");
            }
        }
        String text = joined.toString().strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("Der letzte Run dieses Workflows hat keine Ausgabe, "
                    + "aus der sich ein Bericht ableiten ließe.");
        }
        return text.length() <= MAX_SOURCE_CHARS ? text : text.substring(text.length() - MAX_SOURCE_CHARS);
    }

    /** One tool-less call in a private, empty directory. Returns the raw answer — never leaves this class. */
    private String ask(String source) {
        Path workdir = createNeutralWorkdir();
        StepResult call;
        try {
            call = commandExecutor.executeStepOnHost(HostExecutionRequest.builder()
                    .projectPath(workdir.toString())
                    .block(BlockDefinition.builder().type("simple")
                            .config(new HashMap<>(Map.of("command", DashboardReportAssistPrompt.build(source))))
                            .build())
                    .stepIndex(0)
                    .systemPrompt(DashboardReportAssistPrompt.SYSTEM)
                    .timeoutSeconds(config.getTimeoutSeconds())
                    .providerId(PROVIDER)
                    .priority(true)
                    .allowedTools(AiProvider.NO_TOOLS)
                    .build());
        } finally {
            cleanupWorkdir(workdir);
        }
        String answer = call.getOutput() == null ? "" : call.getOutput().strip();
        if (call.getStatus() != StepStatus.COMPLETED || answer.isEmpty()) {
            // The CLI's error text can name paths and settings — it never reaches the caller.
            log.info("dashboard-report assist: the AI call did not complete ({})", call.getStatus());
            throw new IllegalStateException("Die KI hat nicht geantwortet. Bitte noch einmal versuchen.");
        }
        return answer;
    }

    /** Fresh, private directory for exactly one call, so no project CLAUDE.md reaches the prompt. */
    private static Path createNeutralWorkdir() {
        try {
            return Files.createTempDirectory("mysuricates-report-assist-");
        } catch (IOException e) {
            log.warn("Could not create the neutral working directory for a report suggestion", e);
            throw new IllegalStateException("Arbeitsverzeichnis für den Vorschlag nicht anlegbar.", e);
        }
    }

    /** Best-effort recursive delete — a leftover temp dir must never fail the request. */
    private static void cleanupWorkdir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // best effort
        }
    }
}

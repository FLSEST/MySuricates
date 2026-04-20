package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives workflow tags + a coarse category from name + description + block-types via a
 * lightweight CLI call against the {@code suggestions-model} setting — the same pattern as
 * {@link com.automation.service.assistant.AssistantActionSuggestionService}. Never hardcodes
 * a model so the user can switch via Settings.
 *
 * <p>Triggered (a) once at startup for all workflows whose {@code tags_generated_at} is null,
 * (b) on workflow save when content changed and tags are not user-edited. Failures are
 * non-fatal — the workflow simply stays untagged until the next attempt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTaggingService {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MIN_TAGS = 2;
    private static final int MAX_TAGS = 5;

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "Productivity", "Dev", "Content", "Analysis",
            "Ops", "Data", "Integration", "Other"
    );

    private static final String SYSTEM_PROMPT = """
            Du klassifizierst MySuricates-Workflows.
            Antworte AUSSCHLIESSLICH mit einem JSON-Objekt — kein Markdown, kein ```-Fence, keine Erklärungen.

            Schema (exakt):
              {"category":"<eine der erlaubten>","tags":["tag1","tag2","tag3"]}

            Erlaubte Kategorien (nutze GENAU diese Strings, keine Varianten):
              Productivity, Dev, Content, Analysis, Ops, Data, Integration, Other

            Regeln für tags:
            - 2 bis 5 Tags, kurz (1–2 Wörter), lowercase, in Englisch.
            - Beschreibe Zweck/Domain, nicht die Technik (lieber "log-analysis" als "shell-block").
            - Keine Duplikate, keine Stopwords, keine Sonderzeichen außer "-".
            """;

    private final CommandExecutorService commandExecutorService;
    private final AppSettingsRepository appSettingsRepository;
    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    @Value("${automation.discovery.auto-tagging-enabled:true}")
    private boolean autoTaggingEnabled;

    private volatile String cachedSuggestionsModel = null;

    @PostConstruct
    public void init() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
    }

    /** Called by {@code SystemSettingsService} when {@code suggestions-model} changes. */
    public void refreshCache() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
        log.info("Workflow-tagging cache refreshed: model={}", cachedSuggestionsModel);
    }

    public boolean isEnabled() {
        return autoTaggingEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        backfillUntagged();
    }

    @Async
    @EventListener
    public void onContentChanged(WorkflowContentChangedEvent event) {
        if (!autoTaggingEnabled) return;
        try {
            tagSingle(event.workflowId());
        } catch (Exception e) {
            log.debug("Async tagging on content-change failed for {}", event.workflowId(), e);
        }
    }

    /** Runs after the application starts. Tags any workflow whose tagsGeneratedAt is null. */
    @Async
    public void backfillUntagged() {
        if (!autoTaggingEnabled) {
            log.debug("Auto-tagging disabled; skipping backfill.");
            return;
        }
        List<Workflow> todo = workflowRepository.findUntagged();
        if (todo.isEmpty()) return;
        log.info("Auto-tagging backfill: {} workflows pending", todo.size());
        int ok = 0, fail = 0;
        for (Workflow w : todo) {
            try {
                if (tagSingle(w.getId())) ok++;
                else fail++;
            } catch (Exception e) {
                fail++;
                log.debug("Backfill tagging failed for {}", w.getId(), e);
            }
        }
        log.info("Auto-tagging backfill done: ok={}, fail={}", ok, fail);
    }

    /**
     * Attempts to tag one workflow by id. Skips if user-edited or disabled.
     * Returns true on success, false otherwise. Runs in its own transaction so a
     * failure doesn't poison the outer request.
     */
    @Transactional
    public boolean tagSingle(String workflowId) {
        if (!autoTaggingEnabled) return false;
        Workflow w = workflowRepository.findById(workflowId).orElse(null);
        if (w == null) return false;
        if (w.isTagsUserEdited()) return false;

        TagResult result = callModel(w);
        if (result == null) return false;

        w.setCategory(result.category);
        w.setTags(new ArrayList<>(result.tags));
        w.setTagsGeneratedAt(Instant.now());
        workflowRepository.save(w);
        return true;
    }

    // ── CLI call ─────────────────────────────────────────────────────────────

    private TagResult callModel(Workflow w) {
        String prompt = buildPrompt(w);
        try {
            var result = commandExecutorService.executeOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(System.getProperty("user.home"))
                            .command(prompt)
                            .systemPrompt(SYSTEM_PROMPT)
                            .model(cachedSuggestionsModel)
                            .timeoutSeconds(TIMEOUT_SECONDS)
                            .priority(false)
                            .build()
            );
            if (result.exitCode() != 0) {
                log.debug("Tagging CLI exited {} for workflow {}", result.exitCode(), w.getId());
                return null;
            }
            return parseAndValidate(result.output());
        } catch (Exception e) {
            log.debug("Tagging CLI failed for workflow {}", w.getId(), e);
            return null;
        }
    }

    private String buildPrompt(Workflow w) {
        var sb = new StringBuilder(512);
        sb.append("Workflow:\n");
        sb.append("- name: ").append(nullSafe(w.getName())).append('\n');
        String desc = nullSafe(w.getDescription());
        if (desc.length() > 800) desc = desc.substring(0, 800) + "…";
        sb.append("- description: ").append(desc).append('\n');
        if (w.getBlockTypes() != null && !w.getBlockTypes().isBlank()) {
            sb.append("- blockTypes: ").append(w.getBlockTypes()).append('\n');
        }
        var skills = w.getActivatedSkills();
        if (skills != null && !skills.isEmpty()) {
            String skillNames = skills.stream()
                    .map(s -> s.getName() == null ? "" : s.getName())
                    .filter(s -> !s.isBlank())
                    .limit(10)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            if (!skillNames.isEmpty()) {
                sb.append("- skills: ").append(skillNames).append('\n');
            }
        }
        sb.append("\nErzeuge jetzt das JSON-Objekt mit category und tags.");
        return sb.toString();
    }

    private TagResult parseAndValidate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    raw.substring(start, end + 1), new TypeReference<>() {});
            Object categoryObj = parsed.get("category");
            Object tagsObj = parsed.get("tags");
            if (!(categoryObj instanceof String category) || !ALLOWED_CATEGORIES.contains(category)) {
                return null;
            }
            if (!(tagsObj instanceof List<?> rawTags)) {
                return null;
            }
            List<String> tags = rawTags.stream()
                    .filter(o -> o instanceof String s && !s.isBlank())
                    .map(o -> ((String) o).trim().toLowerCase().replaceAll("[^a-z0-9-]", ""))
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .limit(MAX_TAGS)
                    .toList();
            if (tags.size() < MIN_TAGS) return null;
            return new TagResult(category, tags);
        } catch (Exception e) {
            log.debug("Could not parse tagging JSON: {}", raw, e);
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private record TagResult(String category, List<String> tags) {}
}

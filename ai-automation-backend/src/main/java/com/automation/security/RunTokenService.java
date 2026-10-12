package com.automation.security;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.util.RunAccess;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Issues and revokes run tokens (TODO cfb5d56b): one random, read-only token per run whose workflow asks
 * for it with {@code metadata.permissions: ["read"]}, valid from the start of the run until its end —
 * issued by {@code ExecutionContextFactory.create} on the run's thread, revoked first thing in
 * {@code RunFinalizer.finish}, before the completion listeners run.
 *
 * <p>Kept in memory only, like the WebSocket tickets: no database row, so no backup or export can carry
 * one, and a restart ends every token. The holder may only read the allowlisted GET endpoints
 * ({@code security.RunTokenPaths}); writing stays with the blocks and the definition gateway, so a step
 * cannot bypass seal and hardening with it. The module {@code run-tokens} switches issuing off (startup
 * configuration only).
 */
@Service
public class RunTokenService {

    private static final int TOKEN_BYTES = 32;

    private final ModuleRegistry modules;
    private final ActivityLogService activityLog;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Issued> byToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByRun = new ConcurrentHashMap<>();

    /** A live token: its run and how often it read which area (first path segment after /api/v1). */
    private record Issued(String runId, Map<String, Integer> reads) {
    }

    public RunTokenService(ModuleRegistry modules, ActivityLogService activityLog) {
        this.modules = modules;
        this.activityLog = activityLog;
    }

    /** Does the workflow ask for a token? See {@link RunAccess#requested}. */
    public static boolean requested(Map<String, Object> workflowMetadata) {
        return RunAccess.requested(workflowMetadata);
    }

    /**
     * A token for the run, if its workflow asks for one and the module is on. A token the run still holds
     * (a resumed run) is replaced. The caller pairs it with the API base ({@link RunAccess.Grant}).
     */
    public Optional<String> issue(String runId, Map<String, Object> workflowMetadata) {
        if (!requested(workflowMetadata) || !modules.isEnabled(ModuleCatalog.RUN_TOKENS)) {
            return Optional.empty();
        }
        drop(runId);
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byToken.put(token, new Issued(runId, new ConcurrentHashMap<>()));
        tokenByRun.put(runId, token);
        RunAccess.register(token);
        return Optional.of(token);
    }

    /** The run a live token belongs to; empty for an unknown, revoked or missing token. */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Issued issued = byToken.get(token);
        return issued == null ? Optional.empty() : Optional.of(issued.runId());
    }

    /** Counts one allowed read, for the summary written when the run ends. */
    public void recordRead(String token, String path) {
        Issued issued = byToken.get(token);
        if (issued != null) {
            issued.reads().merge(area(path), 1, Integer::sum);
        }
    }

    /**
     * Ends the run's token — called first when the run ends, before its completion listeners run — and
     * writes how much it read. Harmless for a run without a token.
     */
    public void revoke(String runId, String workflowId) {
        Issued issued = drop(runId);
        if (issued == null || issued.reads().isEmpty()) {
            return;
        }
        int total = issued.reads().values().stream().mapToInt(Integer::intValue).sum();
        String byArea = new TreeMap<>(issued.reads()).entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        activityLog.info(LogCategory.SECURITY, "Run token: " + total + " read(s) (" + byArea + "), revoked at run end",
                "RunTokenService", runId, workflowId);
    }

    private Issued drop(String runId) {
        String token = tokenByRun.remove(runId);
        if (token == null) {
            return null;
        }
        RunAccess.unregister(token);
        return byToken.remove(token);
    }

    private static String area(String path) {
        String rest = path.startsWith("/api/v1/") ? path.substring("/api/v1/".length()) : path;
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}

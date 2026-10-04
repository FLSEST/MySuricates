package com.automation.modules;

import com.automation.exception.ErrorMessage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.automation.modules.ModuleMaturity.EXPERIMENTAL;
import static com.automation.modules.ModuleMaturity.LEGACY;
import static com.automation.modules.ModuleMaturity.STABLE;
import static com.automation.modules.ModuleRisk.CREDENTIALS;
import static com.automation.modules.ModuleRisk.HOST_ACCESS;
import static com.automation.modules.ModuleRisk.NETWORK;
import static com.automation.modules.ModuleTier.ADVANCED;
import static com.automation.modules.ModuleTier.HARDENING;
import static com.automation.modules.ModuleTier.RESEARCH;
import static com.automation.modules.ModuleTier.STANDARD;

/**
 * Single source of truth for every optional module and hardening switch.
 *
 * <p>Everything not listed here is core and always on. Modules are decided at startup only
 * ({@code automation.modules.*}, {@code automation.hardening.*}) — deliberately not via REST,
 * so a leaked API key cannot re-enable network, credential or host-access features.
 */
public enum ModuleCatalog {

    // ── Standard: on in every preset, switchable off ─────────────────────────
    DASHBOARD_REPORT("dashboard-report", STANDARD, STABLE, true, Set.of(), List.of(),
            "Dashboard report block: a workflow fills the dashboard result contract from block "
                    + "config instead of emitting a marker, plus the editor's AI proposal for those fields."),
    GOALS("goals", STANDARD, STABLE, true, Set.of(), List.of(),
            "Goals: projects, work packages, milestones, TODO planning (size, effort, question/decision), "
                    + "goal workflows and the goal guard."),

    // ── Research ─────────────────────────────────────────────────────────────
    CHAINS("chains", RESEARCH, STABLE, false, Set.of(), List.of(),
            "Chain several workflows into a sequential pipeline."),
    ASSISTANT("assistant", RESEARCH, EXPERIMENTAL, false, Set.of(), List.of(),
            "Chat assistant and page suggestions that can read and edit platform objects."),
    LEARNING("learning", RESEARCH, EXPERIMENTAL, false, Set.of(), List.of(),
            "Run insights, recommendations and block-type statistics."),
    MEMORY("memory", RESEARCH, EXPERIMENTAL, false, Set.of(), List.of(),
            "Workflow memory: knowledge extracted from runs and injected into later runs."),
    AUDIT("audit", RESEARCH, EXPERIMENTAL, false, Set.of(), List.of(),
            "Audit center: findings from audit workflows, reports and promotion to TODOs."),
    BPMN("bpmn", RESEARCH, EXPERIMENTAL, false, Set.of(), List.of(),
            "Export workflows as BPMN and import MySuricates BPMN exports."),

    // ── Advanced: outward-facing ─────────────────────────────────────────────
    INTEGRATIONS("integrations", ADVANCED, STABLE, false, Set.of(CREDENTIALS, NETWORK), List.of(),
            "External services (GitHub, Jira, Trello, Google, Confluence, Taskwarrior): OAuth, integration blocks, TODO tracker export, calendar."),
    HTTP_API("http-api", ADVANCED, EXPERIMENTAL, false, Set.of(CREDENTIALS, NETWORK), List.of("integrations"),
            "HTTP API connection: outbound HTTP calls from integration blocks to services you enter yourself "
                    + "(base URL + optional auth header, several services as named connections)."),
    WEBHOOKS("webhooks", ADVANCED, STABLE, false, Set.of(NETWORK), List.of(),
            "Inbound webhook triggers that start workflows from outside."),
    REMOTE_ACCESS("remote-access", ADVANCED, STABLE, false, Set.of(NETWORK), List.of(),
            "Remote access to this instance via Tailscale."),
    TERMINAL("terminal", ADVANCED, STABLE, false, Set.of(HOST_ACCESS), List.of(),
            "Interactive terminal into a run's container over WebSocket."),
    STAGING("staging", ADVANCED, EXPERIMENTAL, false, Set.of(HOST_ACCESS, NETWORK), List.of(),
            "Stable and test instances in Docker, definition sync between instances."),

    // ── Advanced: low use / legacy ───────────────────────────────────────────
    CONVERSION("conversion", ADVANCED, LEGACY, false, Set.of(), List.of(),
            "Turn a finished run into a reusable workflow."),
    DIALOG_IMPORT("dialog-import", ADVANCED, LEGACY, false, Set.of(), List.of("conversion"),
            "Import a chat dialog and convert it into a workflow."),
    ONBOARDING_PROFILES("onboarding-profiles", ADVANCED, LEGACY, false, Set.of(), List.of(),
            "Onboarding profiles for projects."),
    SKILLS("skills", ADVANCED, LEGACY, false, Set.of(HOST_ACCESS), List.of(),
            "Skill packages and the skill-capability block."),
    DEBUG("debug", ADVANCED, LEGACY, false, Set.of(), List.of(),
            "Lists workflows whose last run failed, with diagnosis and restart."),
    FACTORY("factory", ADVANCED, EXPERIMENTAL, false, Set.of(), List.of(),
            "Generate workflows from a description and auto-fix failing ones."),
    WISHES("wishes", ADVANCED, EXPERIMENTAL, false, Set.of(), List.of("chains"),
            "Wishes: free-text goals decomposed into workflows and chains."),

    // ── Hardening (automation.hardening.*, independent of presets) ───────────
    PROVENANCE("provenance", HARDENING, EXPERIMENTAL, true, Set.of(), List.of(),
            "Record a SHA-256 hash of the workflow definition and its version on every run."),
    SEALING("sealing", HARDENING, EXPERIMENTAL, false, Set.of(), List.of("provenance"),
            "Seal workflows: sealed definitions reject edits and refuse to run if tampered with."),
    REQUIRE_SEALED_FOR_UNATTENDED("require-sealed-for-unattended", HARDENING, EXPERIMENTAL, false, Set.of(),
            List.of("sealing"),
            "Unattended runs (schedules, webhooks, triggers, auto-repair, wishes and the chains or "
                    + "sub-workflows they start) only start sealed workflows."),
    REQUIRE_SIGNED_SEAL("require-signed-seal", HARDENING, EXPERIMENTAL, false, Set.of(), List.of("sealing"),
            "Sealed workflows must carry a valid signature (key outside the database) — an unsigned "
                    + "seal, or one whose signature does not match, refuses its runs."),
    SEAL_IMMUTABLE("seal-immutable", HARDENING, EXPERIMENTAL, false, Set.of(), List.of("sealing"),
            "Unsealing is no longer possible over REST — a seal is then released only by turning this "
                    + "switch off in the startup configuration and restarting. Protects against anyone "
                    + "(including an agent) who holds the API key."),
    WEBHOOK_SIGNATURES("webhook-signatures", HARDENING, EXPERIMENTAL, false, Set.of(), List.of("webhooks"),
            "Per-webhook HMAC-SHA256 signing secret (X-Signature). A stored secret is always enforced; "
                    + "the switch gates setting and removing it."),
    PRIVATE_NETWORK_GUARD("private-network-guard", HARDENING, EXPERIMENTAL, true, Set.of(), List.of(),
            "The iCal feed and RSS/Atom feed integrations refuse targets that resolve to localhost, private "
                    + "networks, link-local (incl. cloud metadata), unspecified or multicast addresses — also "
                    + "on every redirect hop. Turn it off in the startup configuration to fetch local feeds on purpose."),
    PRIVATE_NETWORK_GUARD_HTTP_API("private-network-guard-http-api", HARDENING, EXPERIMENTAL, false, Set.of(),
            List.of(),
            "The same address check for the http-api integration. Off by default, because services in your "
                    + "own network are a common http-api target; turn it on to refuse them as well."),
    PRIVATE_NETWORK_GUARD_OWN_SERVICES("private-network-guard-own-services", HARDENING, EXPERIMENTAL, false,
            Set.of(), List.of(),
            "The same address check for integrations that talk to a self-hosted service at the address you "
                    + "enter (CalDAV, Gitea/Forgejo, GitLab, ntfy) and for webhook notifications. Off by "
                    + "default, because these services usually run in your own network.");

    private final String key;
    private final ModuleTier tier;
    private final ModuleMaturity maturity;
    private final boolean defaultEnabled;
    private final Set<ModuleRisk> risks;
    private final List<String> requiresKeys;
    private final String description;

    ModuleCatalog(String key, ModuleTier tier, ModuleMaturity maturity, boolean defaultEnabled,
                  Set<ModuleRisk> risks, List<String> requiresKeys, String description) {
        this.key = key;
        this.tier = tier;
        this.maturity = maturity;
        this.defaultEnabled = defaultEnabled;
        this.risks = risks;
        this.requiresKeys = requiresKeys;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public ModuleTier tier() {
        return tier;
    }

    public ModuleKind kind() {
        return tier == HARDENING ? ModuleKind.HARDENING : ModuleKind.FEATURE;
    }

    public ModuleMaturity maturity() {
        return maturity;
    }

    /**
     * Default when neither a preset nor an override decides. For feature modules the preset decides,
     * so this flag only documents intent there — a {@link ModuleTier#STANDARD} module carries
     * {@code true} because every preset enables it. For hardening flags it is the actual default.
     */
    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public Set<ModuleRisk> risks() {
        return risks;
    }

    /** Modules that must be enabled as well. Resolved lazily — enum constants cannot forward-reference. */
    public List<ModuleCatalog> requires() {
        return requiresKeys.stream().map(k -> byKey(k).orElseThrow()).toList();
    }

    public String description() {
        return description;
    }

    /** Fully qualified property, e.g. {@code automation.modules.remote-access}. */
    public String property() {
        return kind().propertyPrefix() + "." + key;
    }

    /** User-facing explanation why {@code what} (a block type, endpoint, tool) is unavailable. */
    public String disabledMessage(String what) {
        return disabledErrorMessage().format(disabledParams(what));
    }

    /** The {@link #disabledMessage} as a message the UI translates; feature modules can also be switched at runtime. */
    public ErrorMessage disabledErrorMessage() {
        return kind() == ModuleKind.FEATURE ? ErrorMessage.MODULE_DISABLED_SWITCHABLE : ErrorMessage.MODULE_DISABLED;
    }

    public Map<String, String> disabledParams(String what) {
        return Map.of("what", String.valueOf(what), "module", key, "property", property());
    }

    /** Environment variable form, e.g. {@code AUTOMATION_MODULES_REMOTE_ACCESS}. */
    public String envVar() {
        return property().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    public static Optional<ModuleCatalog> byKey(String key) {
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
    }

    /**
     * Relaxed lookup within one kind: ignores case, dots, dashes and underscores, so
     * {@code remote-access}, {@code remote.access} (from {@code AUTOMATION_MODULES_REMOTE_ACCESS})
     * and {@code remoteaccess} all resolve.
     */
    static Optional<ModuleCatalog> byRelaxedKey(ModuleKind kind, String raw) {
        String wanted = normalize(raw);
        return Arrays.stream(values())
                .filter(m -> m.kind() == kind && normalize(m.key).equals(wanted))
                .findFirst();
    }

    private static String normalize(String s) {
        return s.replaceAll("[-._\\[\\]]", "").toLowerCase(Locale.ROOT);
    }
}

package com.automation.service.ai.status;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.ProviderSetup;
import com.automation.service.ai.status.AiProviderStatusReport.ProviderStatus;
import com.automation.service.ai.status.AiProviderStatusReport.State;
import com.automation.service.system.ProviderLoginCheck;
import com.automation.util.HostProcessUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Per registered AI provider: is its program on the PATH, is a login there, and if not, which command
 * helps (f5a273ae). The same rule the installer applies ({@link ProviderSetup}, scripts/lib/providers.sh),
 * so installer and interface never disagree about one machine.
 *
 * <p>Nothing here runs a CLI: a PATH lookup ({@link HostProcessUtils#isOnPath}), a few environment
 * variables and at most a handful of small files. It is still kept off every page load — the answer is
 * cached for {@code automation.ai-provider-status.ttl-seconds} (default 60) and {@code refresh} looks
 * again at once, e.g. right after logging in. Only the default provider is resolved per call, so a
 * changed setting shows without waiting for the cache. No DB access, no transaction.
 *
 * <p>As {@link ProviderLoginCheck} it answers the startup configuration card with the same rule, the
 * same environment and the same home directory (7296ad45) — freshly, like that card, not from the cache.
 *
 * <p>In a container, the fixed login command of the start path that launched it (if it has one) comes
 * along as {@code MYSURICATES_LOGIN_COMMAND} — the same environment {@code scripts/lib/host-mounts.sh}
 * (or {@code scripts/tryout.sh}, which sets it directly) leaves behind, next to {@code MYSURICATES_RUNTIME}.
 * Read once at construction, like every other environment variable here, and checked against a small
 * allow-list of characters before it ever reaches a response.
 */
@Service
public class AiProviderStatusService implements ProviderLoginCheck {

    private final List<AiProvider> providers;
    private final AiProviderRegistry registry;
    private final HostProcessUtils processUtils;
    private final UnaryOperator<String> env;
    private final Path home;
    private final boolean container;
    private final String loginCommand;
    private final long ttlMs;
    private final LongSupplier clock;
    private final Object lock = new Object();

    private volatile Snapshot snapshot;

    private record Snapshot(long takenAt, List<Probe> probes) {}

    private record Probe(String id, ProviderSetup setup, boolean installed, ProviderLoginProbe.Login login) {

        ProviderStatus status(String defaultProviderId) {
            State state = !installed ? State.NOT_INSTALLED : login == null ? State.LOGIN_MISSING : State.READY;
            return new ProviderStatus(id, setup.cli(), state, installed, login != null,
                    login == null ? null : login.via(), login == null ? null : login.source(),
                    setup.install(), setup.login(), id.equalsIgnoreCase(defaultProviderId), setup.loginNote());
        }
    }

    @Autowired
    public AiProviderStatusService(List<AiProvider> providers, AiProviderRegistry registry,
                                   HostProcessUtils processUtils,
                                   @Value("${automation.instance.container:}") String instanceContainer,
                                   @Value("${automation.ai-provider-status.ttl-seconds:60}") int ttlSeconds) {
        this(providers, registry, processUtils, System::getenv, instanceContainer, ttlSeconds,
                System::currentTimeMillis);
    }

    AiProviderStatusService(List<AiProvider> providers, AiProviderRegistry registry, HostProcessUtils processUtils,
                            UnaryOperator<String> env, String instanceContainer, int ttlSeconds, LongSupplier clock) {
        this.providers = providers.stream().sorted(Comparator.comparing(AiProvider::getId)).toList();
        this.registry = registry;
        this.processUtils = processUtils;
        this.env = env;
        this.home = homeOf(env);
        // scripts/lib/host-mounts.sh marks its containers; compose files and tryout.sh name the container
        this.container = "container".equals(env.apply("MYSURICATES_RUNTIME"))
                || (instanceContainer != null && !instanceContainer.isBlank());
        this.loginCommand = this.container ? sanitizeLoginCommand(env.apply("MYSURICATES_LOGIN_COMMAND")) : null;
        this.ttlMs = Math.max(0, ttlSeconds) * 1000L;
        this.clock = clock;
    }

    /** @param refresh look at the machine now instead of answering from the cache */
    public AiProviderStatusReport report(boolean refresh) {
        Snapshot current = snapshot;
        if (refresh || isStale(current)) {
            synchronized (lock) {
                current = snapshot;
                if (refresh || isStale(current)) {
                    current = probe();
                    snapshot = current;
                }
            }
        }
        String defaultProviderId = registry.getDefaultProviderId();
        return new AiProviderStatusReport(container, loginCommand, Instant.ofEpochMilli(current.takenAt()),
                current.probes().stream().map(p -> p.status(defaultProviderId)).toList());
    }

    @Override
    public Boolean loggedIn(String cli) {
        return providers.stream().map(AiProvider::setup).filter(setup -> setup.cli().equals(cli)).findFirst()
                .map(setup -> ProviderLoginProbe.find(setup, env, home).isPresent())
                .orElse(null);
    }

    // Letters, digits, spaces and the punctuation a start path's own login command actually needs
    // (a path, "login", a provider list in brackets separated by "|"): scripts/tryout.sh login
    // [claude|codex|pi], scripts/instance.sh login, scripts/staging.sh login stable. Nothing here is
    // ever run — only checked before it leaves this service, in case the variable was ever tampered with.
    private static final Pattern LOGIN_COMMAND_ALLOWED = Pattern.compile("[A-Za-z0-9 ./_\\-\\[\\]|]+");

    private static String sanitizeLoginCommand(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return !trimmed.isEmpty() && LOGIN_COMMAND_ALLOWED.matcher(trimmed).matches() ? trimmed : null;
    }

    private boolean isStale(Snapshot current) {
        return current == null || clock.getAsLong() - current.takenAt() >= ttlMs;
    }

    private Snapshot probe() {
        long now = clock.getAsLong();
        List<Probe> probes = providers.stream().map(provider -> {
            ProviderSetup setup = provider.setup();
            return new Probe(provider.getId(), setup, processUtils.isOnPath(setup.cli()),
                    ProviderLoginProbe.find(setup, env, home).orElse(null));
        }).toList();
        return new Snapshot(now, probes);
    }

    /**
     * {@code $HOME}, because that is where the CLIs look: they inherit this process's environment.
     * {@code user.home} only as a fallback — an instance started with {@code -Duser.home} for its own
     * data still runs its CLIs with the real home directory.
     */
    static Path homeOf(UnaryOperator<String> env) {
        String home = env.apply("HOME");
        return Path.of(home == null || home.isBlank() ? System.getProperty("user.home") : home);
    }
}

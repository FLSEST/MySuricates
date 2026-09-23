package com.automation.service.system;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * This instance's own addresses in its tailnet, for the origin check (a8056256): the IPv4 from
 * {@code tailscale ip -4} and the MagicDNS name from {@code tailscale status --json}
 * ({@code Self.DNSName}). Only while the module {@code remote-access} is on; without Tailscale
 * (not installed, stopped, logged out) the answer is simply empty. Peers are never listed — a
 * foreign host has no way in through this class.
 *
 * <p>Asked on demand and kept for {@link #TTL}: the check runs on the request path, and an address
 * Tailscale hands out after the backend started shows up without a restart.
 */
@Service
@Slf4j
public class OwnTailnetAddresses {

    static final Duration TTL = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern HOSTNAME = Pattern.compile(
            "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

    /** Read per call: the module can be switched at runtime (automation.modules.ui-editable). */
    private final BooleanSupplier enabled;
    private final TailscaleSelfQuery tailscale;
    private final LongSupplier nanoTime;
    /** Not {@code synchronized}: the lock is held across a process call, and request threads are virtual. */
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Snapshot snapshot;

    @Autowired
    public OwnTailnetAddresses(ModuleRegistry modules, TailscaleSelfQuery tailscale) {
        this(() -> modules.isEnabled(ModuleCatalog.REMOTE_ACCESS), tailscale, System::nanoTime);
    }

    OwnTailnetAddresses(BooleanSupplier enabled, TailscaleSelfQuery tailscale, LongSupplier nanoTime) {
        this.enabled = enabled;
        this.tailscale = tailscale;
        this.nanoTime = nanoTime;
    }

    /** Hosts in {@link #normalizeHost} form; empty while remote-access is off or Tailscale is not up. */
    public Set<String> hosts() {
        if (!enabled.getAsBoolean()) {
            return Set.of();
        }
        Snapshot current = snapshot;
        if (current != null && current.isFresh(nanoTime.getAsLong())) {
            return current.hosts();
        }
        lock.lock();
        try {
            current = snapshot;
            if (current == null || !current.isFresh(nanoTime.getAsLong())) {
                current = new Snapshot(resolve(), nanoTime.getAsLong());
                snapshot = current;
            }
            return current.hosts();
        } finally {
            lock.unlock();
        }
    }

    /** Lower case, without the trailing dot MagicDNS names carry ({@code host.tailnet.ts.net.}). */
    public static String normalizeHost(String host) {
        String lower = host.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private Set<String> resolve() {
        Set<String> hosts = new LinkedHashSet<>();
        tailscale.ipv4().ifPresent(output -> output.lines()
                .map(String::strip)
                .filter(IPV4.asMatchPredicate())
                .forEach(hosts::add));
        tailscale.statusJson().flatMap(OwnTailnetAddresses::selfDnsName).ifPresent(hosts::add);
        log.debug("Own tailnet addresses: {}", hosts);
        return Set.copyOf(hosts);
    }

    private static Optional<String> selfDnsName(String statusJson) {
        try {
            String name = normalizeHost(JSON.readTree(statusJson).path("Self").path("DNSName").asText(""));
            return HOSTNAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
        } catch (JsonProcessingException e) {
            log.debug("tailscale status --json not readable: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private record Snapshot(Set<String> hosts, long resolvedAtNanos) {
        boolean isFresh(long now) {
            return now - resolvedAtNanos < TTL.toNanos();
        }
    }
}

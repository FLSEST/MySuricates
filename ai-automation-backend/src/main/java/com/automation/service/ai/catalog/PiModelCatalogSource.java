package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers pi models via {@code pi --offline --list-models}.
 *
 * <p>pi already filters that table to backends the user has credentials for
 * ({@code ~/.pi/agent/auth.json}) or has defined in {@code models.json}, so every row is
 * runnable. Values use this app's {@code <backend>/<model-id>} routing convention
 * (see {@code HostPiService.parseModel}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PiModelCatalogSource implements ModelCatalogSource {

    public static final String SOURCE = "pi-cli";
    static final List<String> COMMAND = List.of("pi", "--offline", "--list-models");

    private final ModelCatalogConfig config;
    private final CliCommandRunner runner;

    @Override
    public String providerId() {
        return "PI";
    }

    @Override
    public ModelCatalog fetch() {
        String output;
        try {
            output = runner.run(COMMAND, config.getProbeTimeoutSeconds());
        } catch (IOException e) {
            log.warn("pi model catalog unavailable: {}", e.getMessage());
            return ModelCatalog.unavailable(providerId(), SOURCE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("pi model catalog probe failed", e);
            return ModelCatalog.unavailable(providerId(), SOURCE, e.toString());
        }
        return ModelCatalog.available(providerId(), SOURCE, Instant.now(), parseTable(output));
    }

    /**
     * Parses pi's fixed-width table:
     * <pre>
     * provider      model                context  max-out  thinking  images
     * openai-codex  gpt-5.5              272K     128K     yes       yes
     * </pre>
     * Lines before the header are ignored (pi may print notices first).
     */
    static List<ModelCatalog.ModelOption> parseTable(String output) {
        List<ModelCatalog.ModelOption> result = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        boolean headerSeen = false;
        for (String raw : output.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!headerSeen) {
                if (line.startsWith("provider")) {
                    headerSeen = true;
                }
                continue;
            }
            String[] t = line.split("\\s+");
            if (t.length < 2) {
                continue;
            }
            String provider = t[0];
            String model = t[1];
            Integer context = t.length > 2 ? parseTokenCount(t[2]) : null;
            Boolean thinking = t.length > 4 ? "yes".equalsIgnoreCase(t[4]) : null;
            result.add(new ModelCatalog.ModelOption(
                    provider + "/" + model,
                    provider + " · " + model,
                    null,
                    context,
                    thinking));
        }
        return result;
    }

    /** {@code 272K} → 272000, {@code 1M} → 1000000, {@code 200000} → 200000, anything else → null. */
    static Integer parseTokenCount(String s) {
        if (s == null || s.isBlank() || s.equals("-")) {
            return null;
        }
        String v = s.trim().toUpperCase();
        try {
            if (v.endsWith("K")) {
                return (int) Math.round(Double.parseDouble(v.substring(0, v.length() - 1)) * 1_000);
            }
            if (v.endsWith("M")) {
                return (int) Math.round(Double.parseDouble(v.substring(0, v.length() - 1)) * 1_000_000);
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

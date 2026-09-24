package com.automation.service.integration.providers.gitea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns label names into the numeric label IDs the Gitea API expects when creating an issue or
 * replacing its labels. Numeric entries pass through unchanged (that is also the way to use
 * organisation labels, which the repository label list does not contain).
 */
final class GiteaLabels {

    /** Upper bound on label pages fetched per call (50 labels each). */
    private static final int MAX_PAGES = 20;

    private GiteaLabels() {}

    /**
     * @param repoApiUrl {@code <base>/api/v1/repos/{owner}/{repo}}
     * @param strict     {@code true}: an unknown name fails the call before anything is written;
     *                   {@code false}: unknown names are dropped (TODO tags need not exist as labels)
     */
    static List<Long> resolve(GiteaHttpClient http, String repoApiUrl, List<String> labels, String token, boolean strict) {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String label : labels) {
            if (label.matches("\\d+")) ids.add(Long.parseLong(label));
            else names.add(label);
        }
        if (names.isEmpty()) return ids;

        Map<String, Long> known = repoLabels(http, repoApiUrl, token);
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            Long id = known.get(name.toLowerCase(Locale.ROOT));
            if (id != null) ids.add(id);
            else unknown.add(name);
        }
        if (strict && !unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown labels in this repository: " + String.join(", ", unknown)
                    + " (create them in Gitea first or pass label IDs)");
        }
        return ids;
    }

    private static Map<String, Long> repoLabels(GiteaHttpClient http, String repoApiUrl, String token) {
        Map<String, Long> byName = new HashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Object result = http.get(repoApiUrl + "/labels?limit=" + GiteaParams.MAX_LIMIT + "&page=" + page, token, Object.class);
            if (!(result instanceof List<?> list) || list.isEmpty()) break;
            for (Object o : list) {
                if (o instanceof Map<?, ?> label && label.get("name") != null && label.get("id") instanceof Number id) {
                    byName.putIfAbsent(label.get("name").toString().toLowerCase(Locale.ROOT), id.longValue());
                }
            }
            if (list.size() < GiteaParams.MAX_LIMIT) break;
        }
        return byName;
    }
}

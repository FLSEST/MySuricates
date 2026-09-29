package com.automation.service.project.bundle;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Stable keys inside a bundle: a slug of the name, made unique with {@code -2}, {@code -3} … */
final class BundleKeys {

    private static final int MAX_LENGTH = 60;

    private final Set<String> used = new HashSet<>();
    private final String fallback;

    BundleKeys(String fallback) {
        this.fallback = fallback;
    }

    String next(String name) {
        String base = slug(name, fallback);
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            key = base + "-" + n;
        }
        return key;
    }

    /** Lower-case ASCII, umlauts transliterated, everything else a single dash. */
    static String slug(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String s = name.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return s.isEmpty() ? fallback : s;
    }
}

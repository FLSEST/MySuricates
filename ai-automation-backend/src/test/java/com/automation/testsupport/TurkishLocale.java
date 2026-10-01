package com.automation.testsupport;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Runs a piece of code with Turkish as the JVM default locale. There {@code "i".toUpperCase()} is
 * {@code "İ"} and {@code "I".toLowerCase()} is {@code "ı"}, so a parser that upper-cases without
 * {@link Locale#ROOT} no longer finds {@code HIGH}, {@code AI} or {@code CRITICAL} (TODO 80796b2d).
 */
public final class TurkishLocale {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private TurkishLocale() {
    }

    public static <T> T call(Supplier<T> action) {
        Locale previous = Locale.getDefault();
        Locale.setDefault(TURKISH);
        try {
            return action.get();
        } finally {
            Locale.setDefault(previous);
        }
    }
}

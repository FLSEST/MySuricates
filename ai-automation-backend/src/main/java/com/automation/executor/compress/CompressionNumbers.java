package com.automation.executor.compress;

import java.util.Locale;

/** German number formatting for compression notes and headers ("740.808", "−4.102 Zeichen"). */
public final class CompressionNumbers {

    private CompressionNumbers() {
    }

    public static String fmt(long n) {
        return String.format(Locale.GERMANY, "%,d", n);
    }

    public static String chars(long removed) {
        return "−" + fmt(removed) + " Zeichen";
    }

    public static String lines(long removed) {
        return "−" + fmt(removed) + " Zeilen";
    }
}

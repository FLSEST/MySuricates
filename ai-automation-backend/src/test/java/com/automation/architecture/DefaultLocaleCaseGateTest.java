package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: no {@code toUpperCase()}/{@code toLowerCase()} without a locale in the backend. With the
 * default locale, a JVM on a Turkish system turns {@code "high"} into {@code "HİGH"} and
 * {@code "AI"} into {@code "aı"} — enum lookups, provider keys and marker comparisons then miss
 * without an error (TODO a9bf13d5, 80796b2d). Write {@code toUpperCase(Locale.ROOT)}, or go through
 * {@code EnumParameter} for request parameters.
 */
class DefaultLocaleCaseGateTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern DEFAULT_LOCALE_CASE = Pattern.compile("\\.to(Upper|Lower)Case\\(\\)");

    @Test
    void caseConversionsNameTheirLocale() throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (DEFAULT_LOCALE_CASE.matcher(lines.get(i)).find()) {
                        offenders.add(MAIN.relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders)
                .as("toUpperCase()/toLowerCase() depend on the default locale – use Locale.ROOT")
                .isEmpty();
    }
}

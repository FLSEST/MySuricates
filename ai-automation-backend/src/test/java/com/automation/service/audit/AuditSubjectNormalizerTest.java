package com.automation.service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nagelt die Normalisierungsregeln fest, die den Finding-Fingerprint gegen subject-Drift
 * stabilisieren (Audit-TODO 0e19648d).
 */
class AuditSubjectNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            // Methoden-Suffix hinter einem Klassennamen faellt weg
            "ContainerShellService.execute | ContainerShellService",
            "TemplateResolver.resolveShell | TemplateResolver",
            "com.automation.service.Foo.bar | com.automation.service.Foo",
            // Schluesselzusatz hinter dem ersten Doppelpunkt faellt weg
            "application.yml:key-store-password | application.yml",
            "src/main/resources/application.yml:key-store-password | src/main/resources/application.yml",
            // Dateiname mit klein geschriebenem Praefix bleibt ganz (keine Klasse davor)
            "application.yml | application.yml",
            "docker-compose.yml | docker-compose.yml",
            // Pfade mit / bleiben ganz
            "src/main/java/com/automation/Foo.java | src/main/java/com/automation/Foo.java",
            // Paketpfad endet auf einem Klassennamen -> nichts abschneiden
            "com.automation.service.Foo | com.automation.service.Foo",
            // Ohne Punkt bleibt alles stehen
            "ContainerShellService | ContainerShellService",
            // Gross-/Kleinschreibung bleibt unveraendert, ein gross geschriebener Suffix ist keine Methode
            "Application.YML | Application.YML",
    })
    void normalizesSubject(String input, String expected) {
        assertThat(AuditSubjectNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(AuditSubjectNormalizer.normalize("  ContainerShellService.execute \t")).isEqualTo("ContainerShellService");
        assertThat(AuditSubjectNormalizer.normalize("\napplication.yml : key ")).isEqualTo("application.yml");
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertThat(AuditSubjectNormalizer.normalize(null)).isEmpty();
        assertThat(AuditSubjectNormalizer.normalize("")).isEmpty();
        assertThat(AuditSubjectNormalizer.normalize("   ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ContainerShellService.execute", "application.yml:key-store-password",
            "com.automation.service.Foo.bar", "src/main/java/com/automation/Foo.java", "application.yml"})
    void isIdempotent(String input) {
        String once = AuditSubjectNormalizer.normalize(input);
        assertThat(AuditSubjectNormalizer.normalize(once)).isEqualTo(once);
    }
}

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
            // Voll qualifiziert: Methode und Paket fallen weg, es bleibt der Klassenname (87a1dac0)
            "com.automation.service.Foo.bar | Foo",
            // Schluesselzusatz hinter dem ersten Doppelpunkt faellt weg
            "application.yml:key-store-password | application.yml",
            "src/main/resources/application.yml:key-store-password | src/main/resources/application.yml",
            // Dateiname mit klein geschriebenem Praefix bleibt ganz (keine Klasse davor)
            "application.yml | application.yml",
            "docker-compose.yml | docker-compose.yml",
            // Klassenname und Pfad sind dieselbe Identitaet (87a1dac0)
            "src/main/java/com/automation/Foo.java | Foo",
            "com/automation/service/AssistantService.java | AssistantService",
            "service/audit/ManagedTodoWriter.java | ManagedTodoWriter",
            "com/automation/service/AssistantService.java:42 | AssistantService",
            "AssistantService.java | AssistantService",
            "com.automation.service.AssistantService | AssistantService",
            // Frontend-Module bleiben Pfade: ActivityLog.tsx ist nicht die Java-Klasse ActivityLog. Nur die
            // Praefix-Varianten desselben Moduls werden angeglichen.
            "frontend/src/pages/Workflows.tsx | frontend/src/pages/Workflows.tsx",
            "ai-automation-frontend/src/pages/Workflows.tsx | frontend/src/pages/Workflows.tsx",
            "src/pages/Workflows.tsx | frontend/src/pages/Workflows.tsx",
            "ai-automation-frontend/src/api/ErrorText.ts | frontend/src/api/ErrorText.ts",
            "frontend/src/pages/ActivityLog.tsx:42 | frontend/src/pages/ActivityLog.tsx",
            "ActivityLog.tsx | ActivityLog.tsx",
            // Andere Pfade bleiben ganz: klein geschriebene Module gibt es mehrfach, Tests sind keine Klasse
            "frontend/src/api/client.ts | frontend/src/api/client.ts",
            "frontend/src/hooks/index.ts | frontend/src/hooks/index.ts",
            "frontend/src/pages/Todos.test.tsx | frontend/src/pages/Todos.test.tsx",
            "src/main/resources/db/migration/V95__x.sql | src/main/resources/db/migration/V95__x.sql",
            "GET /api/v1/workflows | GET /api/v1/workflows",
            "cycles/component/com.automation.config | cycles/component/com.automation.config",
            "layer/com.automation.controller->com.automation.repository | layer/com.automation.controller->com.automation.repository",
            // Ohne Punkt bleibt alles stehen
            "ContainerShellService | ContainerShellService",
            // Gross-/Kleinschreibung bleibt unveraendert, ein gross geschriebener Suffix ist keine Methode
            "Application.YML | Application.YML",
            // Vor dem Doppelpunkt eine Datei oder Klasse: der Rest ist Position/Schluessel und faellt weg
            "pom.xml:docker-java-core | pom.xml",
            // (Klasse.java faltet wie Klasse.methode auf den Klassennamen)
            "RateLimitFilter.java:47 | RateLimitFilter",
            "ShellBlockExecutor:resolveTemplate | ShellBlockExecutor",
            // Vor dem Doppelpunkt ein klein geschriebenes Art-Wort: <art>:<wert> ist die Identitaet (94fda6ef)
            "package:com.automation.controller | package:com.automation.controller",
            "package:com.automation.service.workflow | package:com.automation.service.workflow",
            "safe-inventory:git-status | safe-inventory:git-status",
            "package : com.automation.controller | package:com.automation.controller",
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
            "com.automation.service.Foo.bar", "src/main/java/com/automation/Foo.java", "application.yml",
            "com/automation/service/AssistantService.java", "ai-automation-frontend/src/pages/Workflows.tsx",
            "src/pages/Workflows.tsx", "ActivityLog.tsx",
            "com.automation.service.AssistantService", "frontend/src/api/client.ts",
            "package : com.automation.controller", "safe-inventory:git-status"})
    void isIdempotent(String input) {
        String once = AuditSubjectNormalizer.normalize(input);
        assertThat(AuditSubjectNormalizer.normalize(once)).isEqualTo(once);
    }

    /** Gleichnamige Klasse in Backend und Frontend: zwei Identitaeten, egal in welcher Schreibweise. */
    @Test
    void sameNameInBackendAndFrontend_staysApart() {
        String backend = AuditSubjectNormalizer.normalize("com/automation/entity/ActivityLog.java");
        assertThat(backend).isEqualTo("ActivityLog");
        assertThat(AuditSubjectNormalizer.normalize("frontend/src/pages/ActivityLog.tsx")).isNotEqualTo(backend);
        assertThat(AuditSubjectNormalizer.normalize("ai-automation-frontend/src/pages/ActivityLog.tsx")).isNotEqualTo(backend);
        assertThat(AuditSubjectNormalizer.normalize("ActivityLog.tsx")).isNotEqualTo(backend);
    }
}

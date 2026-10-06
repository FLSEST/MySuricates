package com.automation.service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the normalization rules that stabilize the finding fingerprint against subject
 * drift (audit TODO 0e19648d).
 */
class AuditSubjectNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            // Method suffix after a class name drops out
            "ContainerShellService.execute | ContainerShellService",
            "TemplateResolver.resolveShell | TemplateResolver",
            // Fully qualified: method and package drop out, the class name remains (87a1dac0)
            "com.automation.service.Foo.bar | Foo",
            // Key suffix after the first colon drops out
            "application.yml:key-store-password | application.yml",
            "src/main/resources/application.yml:key-store-password | src/main/resources/application.yml",
            // Filename with a lowercase prefix stays whole (no class before it)
            "application.yml | application.yml",
            "docker-compose.yml | docker-compose.yml",
            // Class name and path are the same identity (87a1dac0)
            "src/main/java/com/automation/Foo.java | Foo",
            "com/automation/service/AssistantService.java | AssistantService",
            "service/audit/ManagedTodoWriter.java | ManagedTodoWriter",
            "com/automation/service/AssistantService.java:42 | AssistantService",
            "AssistantService.java | AssistantService",
            "com.automation.service.AssistantService | AssistantService",
            // Not a Java FQN: a package segment alone, file extension as the last segment, no CamelCase
            "pom.XML | pom.XML",
            "docs.README | docs.README",
            "x.Yml | x.Yml",
            "config.app.Yml | config.app.Yml",
            "service.Foo | service.Foo",
            "com.example.CONSTANTS | com.example.CONSTANTS",
            // Frontend modules stay paths: ActivityLog.tsx is not the Java class ActivityLog. Only the
            // prefix variants of the same module are aligned.
            "frontend/src/pages/Workflows.tsx | frontend/src/pages/Workflows.tsx",
            "ai-automation-frontend/src/pages/Workflows.tsx | frontend/src/pages/Workflows.tsx",
            "src/pages/Workflows.tsx | frontend/src/pages/Workflows.tsx",
            "ai-automation-frontend/src/api/ErrorText.ts | frontend/src/api/ErrorText.ts",
            "frontend/src/pages/ActivityLog.tsx:42 | frontend/src/pages/ActivityLog.tsx",
            "ActivityLog.tsx | ActivityLog.tsx",
            // Other paths stay whole: lowercase modules exist several times, tests are not a class
            "frontend/src/api/client.ts | frontend/src/api/client.ts",
            "frontend/src/hooks/index.ts | frontend/src/hooks/index.ts",
            "frontend/src/pages/Todos.test.tsx | frontend/src/pages/Todos.test.tsx",
            "src/main/resources/db/migration/V95__x.sql | src/main/resources/db/migration/V95__x.sql",
            "GET /api/v1/workflows | GET /api/v1/workflows",
            "cycles/component/com.automation.config | cycles/component/com.automation.config",
            "layer/com.automation.controller->com.automation.repository | layer/com.automation.controller->com.automation.repository",
            // Without a dot everything stays
            "ContainerShellService | ContainerShellService",
            // Case stays unchanged, an uppercase suffix is not a method
            "Application.YML | Application.YML",
            // Before the colon a file or class: the rest is position/key and drops out
            "pom.xml:docker-java-core | pom.xml",
            // (Klasse.java folds like Klasse.methode onto the class name)
            "RateLimitFilter.java:47 | RateLimitFilter",
            "ShellBlockExecutor:resolveTemplate | ShellBlockExecutor",
            // Before the colon a lowercase kind word: <art>:<wert> is the identity (94fda6ef)
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

    /** Same-named class in backend and frontend: two identities, whatever the spelling. */
    @Test
    void sameNameInBackendAndFrontend_staysApart() {
        String backend = AuditSubjectNormalizer.normalize("com/automation/entity/ActivityLog.java");
        assertThat(backend).isEqualTo("ActivityLog");
        assertThat(AuditSubjectNormalizer.normalize("frontend/src/pages/ActivityLog.tsx")).isNotEqualTo(backend);
        assertThat(AuditSubjectNormalizer.normalize("ai-automation-frontend/src/pages/ActivityLog.tsx")).isNotEqualTo(backend);
        assertThat(AuditSubjectNormalizer.normalize("ActivityLog.tsx")).isNotEqualTo(backend);
    }

    /**
     * Basis of the class-name normalization (87a1dac0): under src/main/java every simple class name is
     * unique. If a second one were added, the audit findings of both classes would fall onto one identity.
     */
    @Test
    void simpleClassNamesUnderSrcMainJavaAreUnique() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java");
        if (!java.nio.file.Files.isDirectory(root)) root = java.nio.file.Paths.get("ai-automation-backend/src/main/java");
        java.util.Map<String, java.util.List<String>> byName = new java.util.TreeMap<>();
        try (var files = java.nio.file.Files.walk(root)) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> byName
                    .computeIfAbsent(f.getFileName().toString(), k -> new java.util.ArrayList<>()).add(f.toString()));
        }
        assertThat(byName).as("Quellbaum gefunden").isNotEmpty();
        assertThat(byName.values().stream().filter(paths -> paths.size() > 1).toList())
                .as("doppelte einfache Klassennamen — AuditSubjectNormalizer setzte ihre Befunde gleich").isEmpty();
    }
}

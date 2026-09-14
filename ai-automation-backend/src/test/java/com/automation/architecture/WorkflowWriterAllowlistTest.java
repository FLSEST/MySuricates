package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: workflow definitions change only through {@code WorkflowService}, which records every change
 * via {@code WorkflowMutationGateway} (version row, server-side change source, hash, activity log).
 * Direct writes elsewhere are pinned here with their reason; a new one fails this test until it is
 * routed through the service or consciously added.
 */
class WorkflowWriterAllowlistTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern DIRECT_WRITE = Pattern.compile(
            "\\bworkflowRepository\\s*\\.\\s*save(All)?\\s*\\(|(?i:\\bupdate\\s+workflows\\s+set\\b)");

    /** file → number of direct writes, each with the reason it may bypass the gateway. */
    private static final Map<String, Integer> ALLOWED = Map.of(
            // the gateway's caller itself: create, update, restore, delete, (de)activate skills
            "com/automation/service/workflow/WorkflowService.java", 6,
            // denormalised block_types column — derived data, not a definition change
            "com/automation/service/learning/BlockTypesBackfillService.java", 1,
            // tags/category labels — not part of the executable definition
            "com/automation/service/workflow/WorkflowTaggingService.java", 1,
            // staging module: test status (label) and pull of instructions/model from the test
            // instance; the pull is detected as an UNTRACKED version on the next tracked change
            "com/automation/service/staging/TestingService.java", 2,
            // seal flag and sealed hash — protects the definition, does not change it
            "com/automation/service/workflow/WorkflowSealService.java", 2,
            // stable_synced_at marker (label)
            "com/automation/service/staging/InstanceSyncHelper.java", 1,
            "com/automation/repository/WorkflowRepository.java", 1);

    @Test
    void directWorkflowWritesAreOnlyTheAllowedOnes() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = DIRECT_WRITE.matcher(Files.readString(file));
                int count = 0;
                while (matcher.find()) {
                    count++;
                }
                if (count > 0) {
                    found.put(MAIN.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }

        assertThat(found).isEqualTo(new TreeMap<>(ALLOWED));
    }
}

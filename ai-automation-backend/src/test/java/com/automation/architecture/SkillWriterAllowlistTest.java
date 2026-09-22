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
 * Gate: skills change only through {@code SkillMutationGateway} (version row, server-side change
 * source, content hash, activity log, refusal while a sealed workflow uses the skill). Counterpart
 * of {@link WorkflowWriterAllowlistTest}; a new direct write fails here until it is routed through
 * the gateway or consciously added with its reason.
 */
class SkillWriterAllowlistTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern DIRECT_WRITE = Pattern.compile(
            "\\b(skill|capability)Repository\\s*\\.\\s*(save(All|AndFlush)?|delete(All|ById|AllById|AllInBatch)?)\\s*\\("
                    + "|(?i:\\b(update\\s+(skills|capabilities)\\s+set|delete\\s+from\\s+(skills|capabilities)|insert\\s+into\\s+(skills|capabilities))\\b)");

    /** file → number of direct writes, each with the reason it may bypass the gateway. */
    private static final Map<String, Integer> ALLOWED = Map.of(
            // the gateway itself: save (create/update, capabilities by cascade) and delete
            "com/automation/service/skill/SkillMutationGateway.java", 2);

    @Test
    void directSkillWritesAreOnlyTheAllowedOnes() throws IOException {
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

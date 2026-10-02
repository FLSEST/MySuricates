package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompressBlockExecutorTest {

    private final CompressBlockExecutor executor = new CompressBlockExecutor();

    /** Mirrors the UI preset "java-log" (frontend {@code components/workflow/compression.ts}). */
    private static final List<Map<String, Object>> JAVA_LOG_STAGES = List.of(
            Map.of("op", "text.normalize"),
            Map.of("op", "lines.filter", "exclude", List.of("^\\s+at ", ":: Spring Boot ::", "^[\\s/\\\\|()_'`=.,-]+$")),
            Map.of("op", "text.normalize"),
            Map.of("op", "lines.replace",
                    "pattern", "^(?:\\d{4}-\\d{2}-\\d{2}[T ][\\d:.,]+Z?|\\d{2}:\\d{2}:\\d{2}[.,]\\d+)\\s*",
                    "replacement", ""),
            Map.of("op", "lines.dedupe", "count", true),
            Map.of("op", "text.truncate", "max_chars", 18000, "keep", "head_tail"));

    private static ExecutionContext contextWithShellSource(String name, StepStatus status, Integer exitCode, String output) {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        record(ctx, name, 0, "shell", status, exitCode, output);
        return ctx;
    }

    private static void record(ExecutionContext ctx, String name, int index, String type, StepStatus status,
                               Integer exitCode, String output) {
        Map<String, Object> md = new HashMap<>();
        md.put("blockType", type);
        if (exitCode != null) md.put("exitCode", exitCode);
        StepResult step = StepResult.builder().stepIndex(index).status(status).output(output)
                .durationMs(312_000L).metadata(md).build();
        ctx.recordOutput(name, output);
        ctx.getSourceSteps().record(name, step);
    }

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("compress").name("backend_kompakt").config(config).build();
    }

    private static String mavenLikeLog() {
        StringBuilder sb = new StringBuilder();
        for (int run = 0; run < 400; run++) {
            sb.append("\n  .   ____          _            __ _ _\n")
              .append(" /\\\\ / ___'_ __ _ _(_)_ __  __ _ \\ \\ \\ \\\n")
              .append(" :: Spring Boot ::               (v3.4.13)\n");
            sb.append("2026-09-14T10:54:").append(String.format("%02d", run % 60))
              .append(".123Z  INFO 4711 --- [main] c.a.SomeTest : Started SomeTest in 1.2 seconds\n");
            sb.append("java.lang.IllegalStateException: expected in test ").append(run % 7).append('\n');
            for (int f = 0; f < 60; f++) {
                sb.append("\tat com.automation.service.Foo").append(f).append(".bar(Foo.java:").append(f).append(")\n");
            }
        }
        sb.append("[ERROR] Tests run: 2140, Failures: 2, Errors: 0, Skipped: 3\n");
        sb.append("[ERROR]   CompressBlockExecutorTest.keepsFailures:42 expected: <1> but was: <2>\n");
        return sb.toString();
    }

    @Test
    void supportsCompressOnly() {
        assertThat(executor.supports("compress")).isTrue();
        assertThat(executor.supports("compress-ai")).isFalse();
    }

    @Test
    void largeMavenLog_isReducedBelowLimit_keepsFailures_originalUntouched() {
        String log = mavenLikeLog();
        assertThat(log.length()).isGreaterThan(740_000);
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.FAILED, 1, log);

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "stages", JAVA_LOG_STAGES, "max_chars", 20000)), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput().length()).isLessThanOrEqualTo(20000);
        assertThat(r.getOutput()).contains("Failures: 2").contains("keepsFailures:42");
        assertThat(r.getOutput()).doesNotContain("\tat com.automation");
        assertThat(ctx.getBlockOutput("backend_tests")).isEqualTo(log);
    }

    /** Pilot run 301e29f4: the lines start with a time only, so lines.replace did not match and dedupe folded nothing. */
    @Test
    void javaLogPreset_foldsLinesThatStartWithATimeOnly() {
        String log = ("21:32:58.761 [main] INFO c.a.SomeTest : Started SomeTest in 1.2 seconds\n").repeat(3)
                + "21:33:01.004 [main] ERROR c.a.Foo : expected in test\n";
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.FAILED, 1, log);

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "stages", JAVA_LOG_STAGES)), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput()).endsWith("\n\n3× [main] INFO c.a.SomeTest : Started SomeTest in 1.2 seconds"
                + "\n[main] ERROR c.a.Foo : expected in test");
    }

    @Test
    void header_usesStoredStatusAndExitCode_notLogText() {
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.FAILED, 1, "BUILD SUCCESS\nalles gut");

        StepResult r = executor.execute(block(Map.of("source", "backend_tests",
                "stages", List.of(Map.of("op", "text.normalize")))), ctx);

        assertThat(r.getOutput()).startsWith("## backend_tests — FAILED · exit 1 · 312 s · 23 → 23 chars\nStages: text.normalize");
        assertThat(r.getOutput()).endsWith("\n\nBUILD SUCCESS\nalles gut");
    }

    @Test
    void metadata_marksDerivedOutput_andDescribesSourceAndStages() {
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.COMPLETED, 0, "a\na\nb");

        StepResult r = executor.execute(block(Map.of("source", "backend_tests",
                "stages", List.of(Map.of("op", "lines.dedupe")))), ctx);

        assertThat(r.getMetadata()).containsEntry("derivedOutput", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> compression = (Map<String, Object>) r.getMetadata().get("compression");
        assertThat(compression).containsEntry("inChars", 5).containsEntry("outChars", 3).containsEntry("capped", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) compression.get("source");
        assertThat(source).containsEntry("block", "backend_tests").containsEntry("exitCode", 0);
        assertThat((List<?>) compression.get("stages")).hasSize(1);
        assertThat(r.getInput()).isEqualTo("compress backend_tests (5 chars): lines.dedupe");
    }

    @Test
    void cap_appliesWhenStagesLeaveTooMuch() {
        ExecutionContext ctx = contextWithShellSource("big", StepStatus.COMPLETED, 0, "x".repeat(10_000));

        StepResult r = executor.execute(block(Map.of("source", "big",
                "stages", List.of(Map.of("op", "text.normalize")), "max_chars", 1000)), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput().length()).isLessThanOrEqualTo(1000);
        assertThat(r.getOutput()).contains("· cap (−");
        assertThat(((Map<?, ?>) r.getMetadata().get("compression")).get("capped")).isEqualTo(true);
    }

    /** Header note and the notice inside the body must name one and the same number of cut characters. */
    @Test
    void cap_headerAndBodyNameTheSameCutNumber() {
        ExecutionContext ctx = contextWithShellSource("big", StepStatus.COMPLETED, 0, "x".repeat(10_000));

        StepResult r = executor.execute(block(Map.of("source", "big",
                "stages", List.of(Map.of("op", "text.normalize")), "max_chars", 1000)), ctx);

        java.util.regex.Matcher header = java.util.regex.Pattern
                .compile("cap \\(−([\\d,]+) chars\\)").matcher(r.getOutput());
        java.util.regex.Matcher body = java.util.regex.Pattern
                .compile("\\[… ([\\d,]+) chars omitted …]").matcher(r.getOutput());
        assertThat(header.find()).isTrue();
        assertThat(body.find()).isTrue();
        assertThat(header.group(1)).isEqualTo(body.group(1));
    }

    @Test
    void skippedSource_isNamedInTheHeader() {
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.SKIPPED, null, "nicht gelaufen");

        StepResult r = executor.execute(block(Map.of("source", "backend_tests",
                "stages", List.of(Map.of("op", "text.normalize")))), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput()).startsWith("## backend_tests — SKIPPED · 312 s · 14 → 14 chars");
        @SuppressWarnings("unchecked")
        Map<String, Object> compression = (Map<String, Object>) r.getMetadata().get("compression");
        assertThat(((Map<?, ?>) compression.get("source")).get("status")).isEqualTo("SKIPPED");
    }

    @Test
    void maxCharsTooSmallForHeader_isConfigError() {
        ExecutionContext ctx = contextWithShellSource("big", StepStatus.COMPLETED, 0, "x".repeat(10_000));

        StepResult r = executor.execute(block(Map.of("source", "big",
                "stages", List.of(Map.of("op", "text.normalize")), "max_chars", 300)), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("'max_chars'");
    }

    @Test
    void unknownSource_ambiguousSource_invalidStage_failWithClearMessage() {
        ExecutionContext ctx = contextWithShellSource("a", StepStatus.COMPLETED, 0, "x");
        record(ctx, "dup", 1, "shell", StepStatus.COMPLETED, 0, "1");
        record(ctx, "dup", 2, "shell", StepStatus.COMPLETED, 0, "2");
        List<Map<String, Object>> stages = List.of(Map.of("op", "text.normalize"));

        StepResult unknown = executor.execute(block(Map.of("source", "fehlt", "stages", stages)), ctx);
        StepResult ambiguous = executor.execute(block(Map.of("source", "dup", "stages", stages)), ctx);
        StepResult invalid = executor.execute(block(Map.of("source", "a", "stages", List.of(Map.of("op", "nope")))), ctx);
        StepResult noSource = executor.execute(block(Map.of("stages", stages)), ctx);

        assertThat(unknown.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(unknown.getOutput()).contains("Source 'fehlt' has not run in this run yet");
        assertThat(ambiguous.getOutput()).contains("ambiguous");
        assertThat(invalid.getOutput()).contains("unknown operation 'nope'");
        assertThat(noSource.getOutput()).contains("'source' is missing");
        assertThat(unknown.getMetadata()).containsEntry("derivedOutput", true);
    }

    @Test
    void emptyContent_isShownAsLeer() {
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.COMPLETED, 0, "a\nb");

        StepResult r = executor.execute(block(Map.of("source", "backend_tests",
                "stages", List.of(Map.of("op", "lines.filter", "include", List.of("ERROR"))))), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput()).endsWith("\n\n(empty)");
    }

    @Test
    void unexpectedRuntimeException_isFailedWithSameShape() {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getSourceSteps()).thenThrow(new IllegalStateException("kaputt"));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "stages", List.of(Map.of("op", "text.normalize")))), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).isEqualTo("Compression failed: IllegalStateException: kaputt");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("error");
        assertThat(r.getCompletedAt()).isNotNull();
    }

    @Test
    void failureWithoutMessage_staysAReadableFailure() {
        ExecutionContext nullMessage = mock(ExecutionContext.class);
        when(nullMessage.getSourceSteps()).thenThrow(new com.automation.executor.compress.CompressionException(null));
        ExecutionContext runtimeWithoutMessage = mock(ExecutionContext.class);
        when(runtimeWithoutMessage.getSourceSteps()).thenThrow(new IllegalStateException());
        Map<String, Object> config = Map.of("source", "backend_tests", "stages", List.of(Map.of("op", "text.normalize")));

        StepResult blank = executor.execute(block(config), nullMessage);
        StepResult runtime = executor.execute(block(config), runtimeWithoutMessage);

        assertThat(blank.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(blank.getOutput()).isEqualTo("Compression failed: unknown error");
        assertThat(blank.getMetadata()).containsEntry("derivedOutput", true).containsEntry("error", "unknown error");
        assertThat(runtime.getOutput()).isEqualTo("Compression failed: IllegalStateException");
    }

    @Test
    void chainOverAnEmptyCompression_doesNotCompressTheLeerMarker() {
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.COMPLETED, 0, "a\nb");
        StepResult first = executor.execute(block(Map.of("source", "backend_tests",
                "stages", List.of(Map.of("op", "lines.filter", "include", List.of("ERROR"))))), ctx);
        assertThat(first.getOutput()).endsWith("\n\n(empty)");
        Map<String, Object> md = new HashMap<>(first.getMetadata());
        md.put("blockType", "compress");
        ctx.recordOutput("backend_kompakt", first.getOutput());
        ctx.getSourceSteps().record("backend_kompakt", StepResult.builder().stepIndex(1).status(first.getStatus())
                .output(first.getOutput()).metadata(md).build());

        StepResult second = executor.execute(BlockDefinition.builder().type("compress").name("noch_kompakter")
                .config(Map.of("source", "backend_kompakt", "stages", List.of(Map.of("op", "text.normalize")))).build(), ctx);

        assertThat(second.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(second.getOutput()).startsWith("## backend_tests (via backend_kompakt) — COMPLETED · exit 0 · 312 s · 0 → 0 chars");
        assertThat(second.getOutput()).endsWith("\n\n(empty)");
        assertThat(((Map<?, ?>) second.getMetadata().get("compression")).get("inChars")).isEqualTo(0);
    }

    @Test
    void previous_resolvesLastRecordedStep() {
        ExecutionContext ctx = contextWithShellSource("a", StepStatus.COMPLETED, 0, "erste");
        record(ctx, null, 1, "simple", StepStatus.COMPLETED, null, "zweite");

        StepResult r = executor.execute(block(Map.of("source", "previous", "stages", List.of(Map.of("op", "text.normalize")))), ctx);

        assertThat(r.getOutput()).startsWith("## step 1 — COMPLETED · 312 s").endsWith("\n\nzweite");
    }

    @Test
    void derivedSource_keepsOriginStatus_andStripsPreviousHeader() {
        ExecutionContext ctx = contextWithShellSource("backend_tests", StepStatus.FAILED, 1, "Z1\nZ2\nZ3");
        StepResult first = executor.execute(block(Map.of("source", "backend_tests",
                "stages", List.of(Map.of("op", "text.normalize")))), ctx);
        Map<String, Object> md = new HashMap<>(first.getMetadata());
        md.put("blockType", "compress");
        StepResult stored = StepResult.builder().stepIndex(1).status(first.getStatus()).output(first.getOutput()).metadata(md).build();
        ctx.recordOutput("backend_kompakt", first.getOutput());
        ctx.getSourceSteps().record("backend_kompakt", stored);

        StepResult second = executor.execute(BlockDefinition.builder().type("compress").name("noch_kompakter")
                .config(Map.of("source", "backend_kompakt", "stages", List.of(Map.of("op", "lines.tail", "lines", 1)))).build(), ctx);

        assertThat(second.getOutput()).startsWith("## backend_tests (via backend_kompakt) — FAILED · exit 1");
        assertThat(second.getOutput()).doesNotContain("Stages: text.normalize");
        assertThat(second.getOutput()).endsWith("\n\n[… 2 lines omitted …]\nZ3");
    }
}

package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: a step whose output is a copy of <em>another</em> step's output must mark itself
 * {@code derivedOutput: true}, so {@code RunCompletionHandler} evaluates its {@code gate_status:}
 * and {@code todo_item:} markers only once — on the step that actually produced them (TODO
 * 52826011, pattern established by 348b7b3b for compression and 6e5d6e2c for sub-workflows).
 *
 * <p>Without this gate a new wrapper block that pipes an earlier output through falls back into
 * double evaluation silently: the run gets a second gate entry and an extra TODO. The test pins
 * both directions — every writer of the marker is listed here, and every block executor that
 * reaches for foreign run/step data is either a listed producer or listed with the reason it does
 * not copy anything.
 *
 * <p>Not covered, and deliberately so: a block that <em>synthesises</em> markers from its own
 * result. {@code ShellBlockExecutor}'s {@code checked_command} prepends its own
 * {@code gate_status:} header — that gate belongs to the shell step itself, is evaluated once and
 * must not be suppressed.
 */
class DerivedOutputProducersTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** {@code "derivedOutput", true} in a metadata map, however it is spelled. */
    private static final Pattern SETS_MARKER = Pattern.compile("\"derivedOutput\"\\s*,\\s*true");

    /** Reading the marker off a stored step. */
    private static final Pattern READS_MARKER = Pattern.compile("get\\(\"derivedOutput\"\\)");

    /** A block executor reaching for another step's or another run's stored result. */
    private static final Pattern TOUCHES_FOREIGN_RUN_DATA = Pattern.compile(
            "getSourceSteps\\(|CompressionSources|getBlockOutput\\(|runRepository|workflowExecutor\\.");

    /** file → number of places that set the marker, each with the output it copies. */
    private static final Map<String, Integer> PRODUCERS = Map.of(
            // compress: header + condensed body of exactly one earlier step
            "com/automation/executor/blocks/CompressBlockExecutor.java", 1,
            // compress-ai: the AI summary of an earlier step, plus the pass-through short-circuit
            "com/automation/executor/blocks/CompressAiBlockExecutor.java", 2,
            // shared header/metadata builder of both compression blocks
            "com/automation/executor/compress/CompressionOutput.java", 1,
            // sub-workflow: a copy of the child run's outputs, already evaluated in the child
            "com/automation/executor/blocks/SubWorkflowBlockExecutor.java", 1);

    /** file → number of places that read the marker. */
    private static final Map<String, Integer> READERS = Map.of(
            // skips derived steps when parsing gate_status/todo_item markers
            "com/automation/service/workflow/RunCompletionHandler.java", 1,
            // follows a derived step back to the original it describes
            "com/automation/executor/SourceStep.java", 1);

    /** Block executors that touch foreign run data without copying an output, with the reason. */
    private static final Map<String, String> NOT_COPYING = Map.of(
            // looks up only the workflow id of the current run; its output is its own transcript
            "com/automation/executor/blocks/AgenticBlockExecutor.java",
            "reads only the current run's workflow id, never another step's output");

    @Test
    void derivedOutputIsSetOnlyByTheKnownProducers() throws IOException {
        assertThat(scan(SETS_MARKER, p -> true))
                .as("every class that marks its output as derived must be listed in PRODUCERS "
                        + "(and every listed one must still do it)")
                .isEqualTo(new TreeMap<>(PRODUCERS));
    }

    @Test
    void derivedOutputIsReadOnlyWhereStepMarkersAreEvaluated() throws IOException {
        assertThat(scan(READS_MARKER, p -> true))
                .as("a new reader of derivedOutput changes how markers are evaluated — list it")
                .isEqualTo(new TreeMap<>(READERS));
    }

    @Test
    void everyBlockExecutorTouchingForeignRunDataIsClassified() throws IOException {
        Set<String> touching = scan(TOUCHES_FOREIGN_RUN_DATA,
                p -> p.getFileName().toString().endsWith("BlockExecutor.java")).keySet();

        List<String> problems = new ArrayList<>();
        for (String file : touching) {
            if (!PRODUCERS.containsKey(file) && !NOT_COPYING.containsKey(file)) {
                problems.add(file + " reads another step's or run's result. If its output is a copy,"
                        + " set metadata \"derivedOutput\" = true and list it in PRODUCERS; otherwise"
                        + " list it in NOT_COPYING with the reason.");
            }
        }
        NOT_COPYING.keySet().stream().filter(file -> !touching.contains(file)).sorted()
                .forEach(file -> problems.add(file + " no longer touches foreign run data"
                        + " — remove it from NOT_COPYING."));

        assertThat(problems).as("classification of output-copying block executors").isEmpty();
    }

    @Test
    void producersAndNonCopyingExecutorsAreDisjointAndExist() {
        assertThat(new TreeSet<>(PRODUCERS.keySet()))
                .doesNotContainAnyElementsOf(NOT_COPYING.keySet());
        Stream.concat(Stream.concat(PRODUCERS.keySet().stream(), READERS.keySet().stream()),
                        NOT_COPYING.keySet().stream())
                .forEach(file -> assertThat(MAIN.resolve(file)).as("listed file %s", file).exists());
    }

    private static Map<String, Integer> scan(Pattern pattern, java.util.function.Predicate<Path> filter)
            throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).filter(filter).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                int count = 0;
                while (matcher.find()) {
                    count++;
                }
                if (count > 0) {
                    found.put(MAIN.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        return found;
    }
}

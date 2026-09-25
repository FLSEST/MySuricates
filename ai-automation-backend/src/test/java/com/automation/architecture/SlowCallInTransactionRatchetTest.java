package com.automation.architecture;

import com.automation.architecture.CompiledMethods.ClassInfo;
import com.automation.architecture.CompiledMethods.MethodRef;
import com.automation.architecture.TransactionalCallScan.Finding;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.HostCodexService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.cli.ShellCommandService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.net.ssl.HttpsURLConnection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the critical rule in CLAUDE.md: no AI, CLI or HTTP call inside a transaction. With
 * HikariCP pool-size 1 a transaction pins the only database connection; a model call of 30–300 s
 * inside it stalls runs, UI and scheduler until it returns, and the pool watchdog fires after 60 s.
 * Read and write in short {@code TransactionTemplate} bursts and call the model in between without a
 * transaction – {@code WorkflowMemoryService#extractKnowledge} shows the shape.
 *
 * <p>Slow calls are the ones that start a process: {@link #PROCESS_CALLS} – every {@code execute*} of
 * {@code CommandExecutorService} (host AI CLI, shell, container CLI), the CLI wrappers behind the
 * providers and the shell runners – plus the two CLI entry points of {@code AiProvider},
 * {@code #reason} and {@code #executeAction}, also when called through an implementing class or on a
 * provider fetched from {@code AiProviderRegistry}. The other {@code AiProvider} methods
 * ({@code getId}, {@code getCapabilities}, {@code setup}, {@code buildConversationPrompt}) start no
 * process and stay allowed. And the ones that wait on a remote server: {@link #HTTP_CALLS} – a
 * webhook target that runs into its timeout holds the connection just as long. Which methods count
 * as transactional, and what the bytecode scan cannot see: {@link TransactionalCallScan}.
 *
 * <p>A ratchet: {@link #KNOWN_EXCEPTIONS} only ever shrinks. A new finding is fixed, not listed.
 */
class SlowCallInTransactionRatchetTest {

    /** Owner → the method names that start a process. */
    static final Map<String, Predicate<String>> PROCESS_CALLS = Map.of(
            "com/automation/service/cli/CommandExecutorService", name -> name.startsWith("execute"),
            "com/automation/service/cli/ShellCommandService", "execute"::equals,
            "com/automation/service/cli/ContainerShellService", "execute"::equals,
            "com/automation/service/ai/HostClaudeService", Set.of("reason", "reasonStructured", "executeAction")::contains,
            "com/automation/service/ai/HostCodexService", "execute"::equals,
            "com/automation/service/ai/HostPiService", Set.of("reason", "executeAction")::contains);
    /** What an HTTP(S) connection waits on: opening it, its streams and every read of the answer. */
    private static final Predicate<String> HTTP_CONNECTION_CALLS = Set.of("connect", "getInputStream",
            "getOutputStream", "getResponseCode", "getResponseMessage", "getHeaderField", "getErrorStream")::contains;
    /**
     * Owner → the method names that send an HTTP request and wait for the answer: the JDK clients the
     * code uses (September 2026 – no RestTemplate, RestClient or WebClient). {@code sendAsync} counts
     * too, its future is usually joined right away.
     *
     * <p>The owner is matched exactly, and javac records the declared type of the receiver, not the
     * class that implements the method. So each type a connection variable can be declared as has its
     * own entry: {@code URLConnection} as {@code URL#openConnection} returns it – only with the calls
     * that open it, cheap for a {@code file:} or {@code jar:} URL, but the scan cannot tell the scheme –
     * and the HTTP and HTTPS subclasses after a cast. Not listed: the other getters that connect
     * implicitly ({@code getContentType}, {@code getHeaderFields}, …), which follow a status or stream
     * read in practice, and connection classes of the code's own (none in September 2026).
     */
    static final Map<String, Predicate<String>> HTTP_CALLS = Map.of(
            "java/net/http/HttpClient", Set.of("send", "sendAsync")::contains,
            "java/net/URLConnection", Set.of("connect", "getInputStream", "getOutputStream")::contains,
            "java/net/HttpURLConnection", HTTP_CONNECTION_CALLS,
            "javax/net/ssl/HttpsURLConnection", HTTP_CONNECTION_CALLS);
    static final String AI_PROVIDER = "com/automation/service/ai/AiProvider";
    static final Set<String> PROVIDER_CALLS = Set.of("reason", "executeAction");

    /** Canonical form {@code "<class>#<method> -> <SlowClass>#<method>"}, each with its reason. */
    static final Set<String> KNOWN_EXCEPTIONS = Set.of();

    /** About 1,300 classes and 210 transactional methods in September 2026; far less is a broken scan. */
    static final int MIN_SCANNED_CLASSES = 500;
    static final int MIN_TRANSACTIONAL_ROOTS = 50;

    @Test
    void noSlowCallRunsInsideATransaction() throws IOException {
        Map<String, ClassInfo> classes = productionClasses();
        assertThat(classes.size()).as("scanned classes").isGreaterThan(MIN_SCANNED_CLASSES);
        assertThat(TransactionalCallScan.countRoots(classes)).as("transactional methods found")
                .isGreaterThan(MIN_TRANSACTIONAL_ROOTS);

        List<String> added = findings(classes).stream().filter(f -> !KNOWN_EXCEPTIONS.contains(f)).toList();

        assertThat(added).as("AI/CLI/HTTP call inside a transaction – with pool-size 1 it holds the only "
                + "DB connection for the whole call. Split into TransactionTemplate bursts (read → call → "
                + "write) instead of extending KNOWN_EXCEPTIONS: %s", added).isEmpty();
    }

    @Test
    void knownExceptionsHoldNoResolvedFinding() throws IOException {
        List<String> found = findings(productionClasses());

        List<String> resolved = KNOWN_EXCEPTIONS.stream().filter(e -> !found.contains(e)).sorted().toList();

        assertThat(resolved).as("no longer found – remove from KNOWN_EXCEPTIONS, otherwise the ratchet "
                + "silently re-permits it").isEmpty();
    }

    @Test
    void scanSeesEveryShapeItGuards() {
        Map<String, ClassInfo> classes = new HashMap<>();
        for (Class<?> fixture : List.of(Fixture.class, ClassLevelFixture.class, FixtureProvider.class)) {
            ClassInfo info = CompiledMethods.read(bytecodeOf(fixture));
            classes.put(info.name, info);
        }
        String fixture = Fixture.class.getName();
        String classLevel = ClassLevelFixture.class.getName();

        assertThat(TransactionalCallScan.findings(classes, slowCalls(classes))).map(Finding::toString)
                .containsExactlyInAnyOrder(
                        fixture + "#direct -> CommandExecutorService#executeOnHost",
                        fixture + "#viaPrivateHelper -> CommandExecutorService#executeStepOnHost",
                        fixture + "#viaLambda -> AiProvider#executeAction",
                        fixture + "#viaMethodReference -> CommandExecutorService#executeOnHost",
                        fixture + "#viaImplementation -> FixtureProvider#reason",
                        fixture + "#viaCliWrapper -> HostCodexService#execute",
                        fixture + "#viaShell -> ShellCommandService#execute",
                        fixture + "#viaShell -> CommandExecutorService#executeStepAsShell",
                        fixture + "#viaHttp -> HttpClient#send",
                        fixture + "#viaHttp -> HttpClient#sendAsync",
                        fixture + "#viaHttp -> HttpURLConnection#getResponseCode",
                        fixture + "#viaUrlConnection -> URLConnection#connect",
                        fixture + "#viaUrlConnection -> HttpsURLConnection#getHeaderField",
                        fixture + "#viaUrlConnection -> HttpURLConnection#getErrorStream",
                        fixture + "#insideTemplate (TransactionTemplate) -> CommandExecutorService#executeOnHost",
                        classLevel + "#inheritsTheClassAnnotation -> CommandExecutorService#executeOnHost");
    }

    static List<String> findings(Map<String, ClassInfo> classes) {
        return TransactionalCallScan.findings(classes, slowCalls(classes)).stream().map(Finding::toString).toList();
    }

    static Predicate<MethodRef> slowCalls(Map<String, ClassInfo> classes) {
        return call -> PROCESS_CALLS.getOrDefault(call.owner(), name -> false).test(call.name())
                || HTTP_CALLS.getOrDefault(call.owner(), name -> false).test(call.name())
                || (PROVIDER_CALLS.contains(call.name())
                        && CompiledMethods.isSubtype(call.owner(), AI_PROVIDER, classes));
    }

    private static Map<String, ClassInfo> productionClasses() throws IOException {
        Path classes = PackageEdges.resolveProductionClasses(
                PackageEdges.codeSourceLocation(), PackageEdges.FALLBACK_CLASS_DIRECTORIES);
        Assumptions.assumeTrue(classes != null, "no compiled production classes next to "
                + PackageEdges.codeSourceLocation() + " – run `mvn test` or `mvn compile` first");
        return CompiledMethods.readDirectory(classes);
    }

    private static byte[] bytecodeOf(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bytecode fixtures of the self-test – read, never run, never Spring beans. */
    @SuppressWarnings("unused")
    static class Fixture {
        CommandExecutorService executor;
        AiProvider provider;
        FixtureProvider implementation;
        HostCodexService codex;
        ShellCommandService shell;
        HttpClient http;
        HttpURLConnection connection;
        TransactionTemplate template;

        @Transactional
        public void direct() {
            executor.executeOnHost(null);
        }

        @Transactional
        public void viaPrivateHelper() {
            helper();
        }

        private void helper() {
            executor.executeStepOnHost(null);
        }

        @Transactional
        public Runnable viaLambda() {
            return () -> {
                try {
                    provider.executeAction(null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }

        @Transactional
        public Optional<?> viaMethodReference(HostExecutionRequest request) {
            return Optional.ofNullable(request).map(executor::executeOnHost);
        }

        @Transactional
        public void viaImplementation() throws IOException {
            implementation.reason(null);
        }

        @Transactional
        public String viaCliWrapper() throws IOException {
            return codex.execute(null, null);
        }

        @Transactional
        public Object viaShell() {
            shell.execute(null, null, 0);
            return executor.executeStepAsShell(null, null, 0, 0);
        }

        @Transactional
        public int viaHttp() throws IOException, InterruptedException {
            http.send(null, null);
            http.sendAsync(null, null);
            return connection.getResponseCode();
        }

        @Transactional
        public Object viaUrlConnection(URLConnection plain, HttpsURLConnection secure) throws IOException {
            plain.connect();
            secure.getHeaderField("Location");
            return connection.getErrorStream();
        }

        @Transactional
        public String cheapProviderCallsStayAllowed() {
            return provider.getId() + provider.getCapabilities();
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void notSupportedRunsWithoutTransaction() {
            executor.executeOnHost(null);
        }

        public Object insideTemplate() {
            return template.execute(status -> executor.executeOnHost(null));
        }

        public void afterTemplate() {
            template.executeWithoutResult(status -> provider.getId());
            executor.executeOnHost(null);
        }
    }

    @Transactional
    @SuppressWarnings("unused")
    static class ClassLevelFixture {
        CommandExecutorService executor;

        public void inheritsTheClassAnnotation() {
            executor.executeOnHost(null);
        }

        @Transactional(propagation = Propagation.NEVER)
        public void neverRunsWithoutTransaction() {
            executor.executeOnHost(null);
        }

        private void privateMethodsAreNoProxyEntry() {
            executor.executeOnHost(null);
        }
    }

    abstract static class FixtureProvider implements AiProvider {
    }
}

package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: outbound HTTP clients come from {@code OutboundHttp} (TODO 8ac3b192). There every client
 * declares its target kind, and the outbound guard, the redirect rule and the connection to the
 * checked address come with it — whether the guard applies no longer depends on someone thinking of
 * it. Building a client anywhere else ({@code HttpClient.newBuilder}, {@code RestTemplate},
 * {@code WebClient}, {@code URL.openConnection}, {@code HttpURLConnection}, a raw {@code Socket} …)
 * fails this test until it moves to the factory or is pinned in {@link #EXEMPT} with its reason. The declared target kind of every client is pinned in
 * {@link #TARGET_KINDS}, so that changing it (e.g. a fixed host that starts taking a URL from the
 * connection) shows up in review.
 */
class OutboundHttpGateTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** The only place that builds a JDK HTTP client. */
    private static final String FACTORY = "com/automation/service/integration/OutboundClient.java";

    private static final Pattern RAW_CLIENT = Pattern.compile(
            "HttpClient\\s*\\.\\s*new(Builder|HttpClient)\\s*\\(|\\bRestTemplate(Builder)?\\b|\\bWebClient\\b"
                    + "|\\bRestClient\\b|\\.\\s*openConnection\\s*\\(|\\.\\s*openStream\\s*\\("
                    + "|\\bHttpURLConnection\\b|\\bHttpClient\\s*\\.\\s*Builder\\b|\\bnew\\s+Socket\\s*\\("
                    + "|\\bjava\\.net\\.Socket\\b");

    /** file → why it may open HTTP connections (or sockets) without the factory. */
    private static final Map<String, String> EXEMPT = Map.of(
            // the own stable/test instance on localhost (staging module), with the dev API key
            "com/automation/service/staging/InstanceHttpClient.java", "own staging instances on localhost",
            "com/automation/service/staging/DockerContainerManager.java", "health probe of the own container",
            // TCP port probe of the instance endpoints the tray shows, no request is sent
            "com/automation/service/tray/TrayInstanceMonitor.java", "port probe of the own instances");

    /** Where integrations and notifications must not hold a raw JDK client at all (not even an injected one). */
    private static final Set<String> NO_RAW_CLIENT_TYPE_UNDER = Set.of(
            "com/automation/service/integration/", "com/automation/service/notification/");

    /** file → declared target kind of its outbound client(s). */
    private static final Map<String, String> TARGET_KINDS = Map.ofEntries(
            Map.entry("com/automation/service/integration/OAuthTokenClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/confluence/ConfluenceProvider.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/github/GitHubHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/google/GoogleCalendarProvider.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/google/GoogleGmailProvider.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/jira/JiraHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/openmeteo/OpenMeteoHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/slack/SlackHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/telegram/TelegramHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/todoist/TodoistHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/trello/TrelloHttpClient.java", "FIXED_HOST"),
            Map.entry("com/automation/service/integration/providers/feed/FeedFetcher.java", "PUBLIC_SOURCE"),
            Map.entry("com/automation/service/integration/providers/icalfeed/ICalFeedFetcher.java", "PUBLIC_SOURCE"),
            Map.entry("com/automation/service/integration/providers/caldav/CalDavHttpClient.java", "OWN_SERVICE"),
            Map.entry("com/automation/service/integration/providers/gitea/GiteaHttpClient.java", "OWN_SERVICE"),
            Map.entry("com/automation/service/integration/providers/gitlab/GitLabHttpClient.java", "OWN_SERVICE"),
            Map.entry("com/automation/service/integration/providers/httpapi/HttpApiClient.java", "OWN_SERVICE"),
            Map.entry("com/automation/service/integration/providers/ntfy/NtfyHttpClient.java", "OWN_SERVICE"),
            Map.entry("com/automation/service/notification/NotificationService.java", "OWN_SERVICE"));

    private static final Map<String, Pattern> DECLARATIONS = Map.of(
            "FIXED_HOST", Pattern.compile("OutboundHttp\\s*\\.\\s*fixedHost\\s*\\("),
            "PUBLIC_SOURCE", Pattern.compile("OutboundTarget\\s*\\.\\s*publicSource\\s*\\("),
            "OWN_SERVICE", Pattern.compile("OutboundTarget\\s*\\.\\s*ownService\\s*\\("));

    @Test
    void httpClientsAreOnlyBuiltByTheFactory_andEveryClientDeclaresItsTargetKind() throws IOException {
        Set<String> rawClients = new TreeSet<>();
        Set<String> rawClientType = new TreeSet<>();
        Map<String, String> kinds = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                if (name.endsWith("/OutboundHttp.java") || name.equals(FACTORY)
                        || name.endsWith("/OutboundTarget.java")) {
                    continue;
                }
                String source = Files.readString(file);
                if (RAW_CLIENT.matcher(source).find()) rawClients.add(name);
                if (NO_RAW_CLIENT_TYPE_UNDER.stream().anyMatch(name::startsWith)
                        && source.contains("java.net.http.HttpClient")) {
                    rawClientType.add(name);
                }
                Set<String> declared = new TreeSet<>();
                DECLARATIONS.forEach((kind, pattern) -> {
                    if (pattern.matcher(source).find()) declared.add(kind);
                });
                if (!declared.isEmpty()) kinds.put(name, String.join("+", declared));
            }
        }

        assertThat(scanned).as("source root not found — run from ai-automation-backend").isGreaterThan(100);
        Set<String> undecided = new TreeSet<>(rawClients);
        undecided.removeAll(EXEMPT.keySet());
        assertThat(undecided)
                .as("own HTTP client outside OutboundHttp: get it from OutboundHttp with its target kind, "
                        + "or pin it in EXEMPT with a reason")
                .isEmpty();
        assertThat(rawClients).as("stale EXEMPT entry — the file no longer opens HTTP connections itself")
                .containsAll(EXEMPT.keySet());
        assertThat(rawClientType)
                .as("integrations and notifications hold an OutboundClient, never a java.net.http.HttpClient")
                .isEmpty();
        assertThat(kinds)
                .as("declared target kind per client (fixed host / public source / own service) — a change "
                        + "here decides whether the outbound guard applies")
                .isEqualTo(new TreeMap<>(TARGET_KINDS));
    }

    @Test
    void thePatternCatchesTheUsualWaysToBuildAClient() {
        for (String line : new String[]{
                "HttpClient.newBuilder()", "HttpClient.newHttpClient()", "new RestTemplate()",
                "RestTemplateBuilder builder", "WebClient.create(url)", "RestClient.builder()",
                "url.openConnection()", "URI.create(x).toURL().openStream()",
                "HttpURLConnection conn = (HttpURLConnection) url.openConnection();",
                "HttpClient.Builder builder = HttpClient.newBuilder();", "java.net.http.HttpClient.Builder b",
                "try (Socket socket = new Socket()) {", "import java.net.Socket;"}) {
            assertThat(RAW_CLIENT.matcher(line).find()).as(line).isTrue();
        }
        assertThat(RAW_CLIENT.matcher("OutboundHttp.fixedHost(Duration.ofSeconds(10))").find()).isFalse();
        assertThat(RAW_CLIENT.matcher("private final OutboundClient httpClient;").find()).isFalse();
        // the Docker daemon client is no outbound HTTP client of an integration
        assertThat(RAW_CLIENT.matcher("new ZerodepDockerHttpClient.Builder()").find()).isFalse();
        assertThat(RAW_CLIENT.matcher("import java.net.SocketAddress;").find()).isFalse();
    }
}

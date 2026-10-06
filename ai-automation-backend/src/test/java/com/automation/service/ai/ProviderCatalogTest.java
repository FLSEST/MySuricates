package com.automation.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The installer reads the AI providers from {@code scripts/lib/provider-catalog.generated.sh} instead of
 * having a hard-wired Claude section (2255f2ee). The source is {@link AiProvider#setup()}
 * of each provider: a new provider appears in the installer without anyone touching the script.
 *
 * <p>Modeled on {@code ModuleCatalogTextsTest}: the test renders the Bash file, a byte comparison
 * makes drift visible. Scalars instead of associative arrays, because macOS ships bash 3.2.
 */
class ProviderCatalogTest {

    private static final Path GENERATED = Path.of("..", "scripts", "lib", "provider-catalog.generated.sh");

    private static final Path DOCKERFILE = Path.of("..", "deploy", "Dockerfile");

    private static final String WRITE_PROPERTY = "provider.catalog.write";

    private static final String REGENERATE_HINT =
            "Neu erzeugen: cd ai-automation-backend && ./mvnw test -Dtest=ProviderCatalogTest -Dprovider.catalog.write=true";

    @Test
    void everyProviderDescribesItsSetupCompletely() throws Exception {
        List<AiProvider> providers = providers();

        assertThat(providers).as("es gibt mindestens einen Anbieter").isNotEmpty();
        assertThat(providers).allSatisfy(provider -> {
            ProviderSetup setup = provider.setup();
            assertThat(setup).as(provider.getId() + ": setup()").isNotNull();
            assertThat(setup.cli()).as(provider.getId() + ": cli").matches("[a-z][a-z0-9-]*");
            assertThat(setup.install()).as(provider.getId() + ": install").isNotBlank();
            assertThat(setup.login()).as(provider.getId() + ": login").isNotBlank();
            // A key, not a sentence: it becomes part of a Bash variable name and an i18n key
            if (setup.loginNote() != null) {
                assertThat(setup.loginNote()).as(provider.getId() + ": loginNote").matches("[a-z][a-z0-9_]*");
            }
            assertThat(setup.loginFiles()).as(provider.getId() + ": loginFiles").isNotEmpty()
                    .allSatisfy(f -> assertThat(f).as("relativ zu $HOME").doesNotStartWith("/").doesNotStartWith("~"));
            // Names end up unquoted in the installer's ${!name} — only uppercase letters, digits, _
            assertThat(setup.loginEnv()).as(provider.getId() + ": loginEnv")
                    .allSatisfy(v -> assertThat(v).matches("[A-Z][A-Z0-9_]*"));
            // Redirectable login directory (status display, f5a273ae): both or neither, and it redirects
            // at least one login file — otherwise the display in the container would look in the wrong place
            assertThat(setup.loginDir() == null).as(provider.getId() + ": loginDir nur mit loginDirEnv")
                    .isEqualTo(setup.loginDirEnv() == null);
            if (setup.loginDirEnv() != null) {
                assertThat(setup.loginDirEnv()).as(provider.getId() + ": loginDirEnv").matches("[A-Z][A-Z0-9_]*");
                assertThat(setup.loginDir()).as(provider.getId() + ": loginDir relativ zu $HOME")
                        .doesNotStartWith("/").doesNotStartWith("~").doesNotEndWith("/");
                assertThat(setup.loginFiles()).as(provider.getId() + ": eine Anmeldedatei liegt in loginDir")
                        .anySatisfy(f -> assertThat(f).startsWith(setup.loginDir() + "/"));
            }
        });
    }

    @Test
    void generatedInstallerCatalogMatchesTheProviders() throws Exception {
        String expected = render(providers());

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(GENERATED.getParent());
            Files.writeString(GENERATED, expected);
        }

        assertThat(GENERATED).as(REGENERATE_HINT).exists();
        assertThat(Files.readString(GENERATED)).as(REGENERATE_HINT).isEqualTo(expected);
    }

    /**
     * The AI CLIs come in a pinned version, in the installer as in the tryout image (pi c3a4cbf6, Claude
     * and Codex 38f30352): a new version can break flags or output format without anyone
     * noticing. Both paths name the same version — the tryout image carries it as {@code ARG}.
     */
    @Test
    void piIsPinnedAlikeInInstallerAndTryoutImage() throws Exception {
        assertThat(PiAiProvider.PINNED_VERSION).as("pi-Version").matches("\\d+\\.\\d+\\.\\d+");
        ProviderSetup pi = new PiAiProvider(mock(HostPiService.class)).setup();
        assertThat(pi.install()).as("Installer (--with-pi)")
                .isEqualTo("npm install -g @earendil-works/pi-coding-agent@" + PiAiProvider.PINNED_VERSION);
        assertPinnedInTryoutImage("PI_VERSION", PiAiProvider.PINNED_VERSION, "@earendil-works/pi-coding-agent");
    }

    @Test
    void claudeIsPinnedAlikeInInstallerAndTryoutImage() throws Exception {
        assertThat(ClaudeAiProvider.PINNED_VERSION).as("Claude-Version").matches("\\d+\\.\\d+\\.\\d+");
        ProviderSetup claude = new ClaudeAiProvider(mock(HostClaudeService.class)).setup();
        // Both paths of the catalog in the same version: the native installer takes it as an argument
        assertThat(claude.install()).as("Installer-Katalog (claude)")
                .isEqualTo("curl -fsSL https://claude.ai/install.sh | bash -s " + ClaudeAiProvider.PINNED_VERSION
                        + "   (or: npm install -g @anthropic-ai/claude-code@" + ClaudeAiProvider.PINNED_VERSION + ")");
        assertPinnedInTryoutImage("CLAUDE_VERSION", ClaudeAiProvider.PINNED_VERSION, "@anthropic-ai/claude-code");
    }

    @Test
    void codexIsPinnedAlikeInInstallerAndTryoutImage() throws Exception {
        assertThat(CodexAiProvider.PINNED_VERSION).as("Codex-Version").matches("\\d+\\.\\d+\\.\\d+");
        ProviderSetup codex = new CodexAiProvider(mock(HostCodexService.class),
                mock(com.automation.config.SummarizationConfig.class)).setup();
        assertThat(codex.install()).as("Installer-Katalog (codex)")
                .isEqualTo("npm install -g @openai/codex@" + CodexAiProvider.PINNED_VERSION);
        assertPinnedInTryoutImage("CODEX_VERSION", CodexAiProvider.PINNED_VERSION, "@openai/codex");
    }

    /** {@code ARG <arg>=<version>} in the tryout stage, the package only with {@code @${<arg>}}, never without a version. */
    private static void assertPinnedInTryoutImage(String arg, String version, String pkg) throws IOException {
        List<String> dockerfile = Files.readAllLines(DOCKERFILE);
        assertThat(dockerfile).as(DOCKERFILE + ": ARG " + arg + " wie PINNED_VERSION")
                .contains("ARG " + arg + "=" + version);
        String installed = String.join("\n", dockerfile);
        assertThat(installed).as(DOCKERFILE + ": die Tryout-Stufe installiert " + pkg + " in dieser Version")
                .contains(" " + pkg + "@${" + arg + "}")
                .doesNotContainPattern(java.util.regex.Pattern.quote(pkg) + "(\\s|$)");
    }

    /**
     * The hint after the login command is a key (eab43a8b) — an English sentence also appeared
     * in the German UI. Every key needs a text in both languages, in the
     * installer as in the UI; otherwise one side would show nothing at all.
     */
    @Test
    void everyLoginNoteIsTranslatedForInstallerAndInterface() throws Exception {
        List<String> notes = providers().stream().map(p -> p.setup().loginNote())
                .filter(java.util.Objects::nonNull).toList();
        assertThat(notes).as("pi meldet sich in pi selbst an: es gibt mindestens einen Hinweis").isNotEmpty();
        for (String lang : List.of("de", "en")) {
            String installer = Files.readString(Path.of("..", "scripts", "lib", "i18n", lang + ".sh"));
            String frontend = Files.readString(Path.of("..", "ai-automation-frontend", "src", "i18n", lang + ".ts"));
            assertThat(notes).allSatisfy(note -> {
                assertThat(installer).as("scripts/lib/i18n/" + lang + ".sh: M_login_note_" + note)
                        .containsPattern("(?m)^M_login_note_" + note + "=\"[^\"]+\"$");
                assertThat(frontend).as("ai-automation-frontend/src/i18n/" + lang + ".ts: loginNotes." + note)
                        .containsPattern("(?m)^\\s+" + note + ": '[^']+',$");
            });
        }
    }

    // ── Finding providers, without a Spring context ──────────────────────────

    /**
     * All shipped AiProviders under com.automation, each with mocks for its constructor parameters.
     * Shipped means: a Spring component. The test provider of the scenario tests
     * ({@code ScriptedAiProvider}, TODO fdf79a86) sits in the same package tree, but does not belong in
     * the installer's catalog.
     */
    private static List<AiProvider> providers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) ->
                new AssignableTypeFilter(AiProvider.class).match(reader, factory)
                        && new AnnotationTypeFilter(Component.class).match(reader, factory));
        List<AiProvider> result = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.automation")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            Object[] args = new Object[constructor.getParameterCount()];
            for (int i = 0; i < args.length; i++) args[i] = mock(constructor.getParameterTypes()[i]);
            constructor.setAccessible(true);
            result.add((AiProvider) constructor.newInstance(args));
        }
        result.sort(Comparator.comparing(p -> p.getId().toLowerCase(Locale.ROOT)));
        return result;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private static String render(List<AiProvider> providers) throws IOException {
        StringBuilder out = new StringBuilder("""
                #!/usr/bin/env bash
                # generiert von ProviderCatalogTest — nicht von Hand ändern
                #
                # Quelle: AiProvider#setup() jedes KI-Anbieters im Backend. Der Installer nennt damit alle
                # Anbieter gleichrangig, ohne einen davon fest zu verdrahten (2255f2ee).
                # Neu erzeugen:
                #   cd ai-automation-backend && ./mvnw test -Dtest=ProviderCatalogTest -Dprovider.catalog.write=true
                #
                # Reine Daten, keine Funktionen; Skalare statt assoziativer Arrays (bash 3.2, macOS).
                # login_files: relativ zu $HOME, durch Leerzeichen getrennt; "datei:muster" heisst, die Datei
                # muss das Muster enthalten. Die erste zutreffende zaehlt als angemeldet.
                # login_env: Umgebungsvariablen, durch Leerzeichen getrennt; eine gesetzte, nicht leere
                # zaehlt ebenfalls als angemeldet (der Wert wird nie ausgegeben).
                # login: nur der Befehl. login_note: was danach zu tun ist, als Schluessel — den Text dazu
                # liefert scripts/lib/i18n/<sprache>.sh (M_login_note_<schluessel>); leer, wenn der Befehl
                # alles sagt (eab43a8b).

                PROVIDER_IDS=(
                """);
        providers.forEach(p -> out.append("  ").append(id(p)).append('\n'));
        out.append(")\n");
        for (AiProvider provider : providers) {
            ProviderSetup setup = provider.setup();
            String prefix = "PROVIDER__" + id(provider) + "__";
            out.append('\n');
            out.append(prefix).append("cli=\"").append(quote(setup.cli())).append("\"\n");
            out.append(prefix).append("install=\"").append(quote(setup.install())).append("\"\n");
            out.append(prefix).append("login=\"").append(quote(setup.login())).append("\"\n");
            out.append(prefix).append("login_files=\"").append(quote(String.join(" ", setup.loginFiles()))).append("\"\n");
            out.append(prefix).append("login_env=\"").append(quote(String.join(" ", setup.loginEnv()))).append("\"\n");
            out.append(prefix).append("login_note=\"").append(quote(setup.loginNote() == null ? "" : setup.loginNote())).append("\"\n");
        }
        return out.toString();
    }

    private static String id(AiProvider provider) {
        return provider.getId().toLowerCase(Locale.ROOT);
    }

    /** Everything bash would still expand inside double quotes. */
    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`");
    }
}

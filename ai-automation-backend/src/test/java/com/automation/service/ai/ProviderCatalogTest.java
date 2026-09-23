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
 * Der Installer liest die KI-Anbieter aus {@code scripts/lib/provider-catalog.generated.sh}, statt
 * einen fest verdrahteten Claude-Abschnitt zu haben (2255f2ee). Quelle ist {@link AiProvider#setup()}
 * jedes Anbieters: ein neuer Anbieter erscheint im Installer, ohne dass jemand das Skript anfasst.
 *
 * <p>Vorbild ist {@code ModuleCatalogTextsTest}: der Test rendert die Bash-Datei, ein Byte-Vergleich
 * macht Drift sichtbar. Skalare statt assoziativer Arrays, weil macOS bash 3.2 mitbringt.
 */
class ProviderCatalogTest {

    private static final Path GENERATED = Path.of("..", "scripts", "lib", "provider-catalog.generated.sh");

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
            assertThat(setup.loginFiles()).as(provider.getId() + ": loginFiles").isNotEmpty()
                    .allSatisfy(f -> assertThat(f).as("relativ zu $HOME").doesNotStartWith("/").doesNotStartWith("~"));
            // Namen landen unquotiert in ${!name} des Installers — nur Grossbuchstaben, Ziffern, _
            assertThat(setup.loginEnv()).as(provider.getId() + ": loginEnv")
                    .allSatisfy(v -> assertThat(v).matches("[A-Z][A-Z0-9_]*"));
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

    // ── Anbieter finden, ohne Spring-Kontext ─────────────────────────────────

    /**
     * Alle ausgelieferten AiProvider unter com.automation, jede mit Mocks fuer ihre Konstruktor-Parameter.
     * Ausgeliefert heisst: eine Spring-Komponente. Der Test-Provider der Szenario-Tests
     * ({@code ScriptedAiProvider}, TODO fdf79a86) liegt im selben Paketbaum, gehoert aber nicht in
     * den Katalog des Installers.
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

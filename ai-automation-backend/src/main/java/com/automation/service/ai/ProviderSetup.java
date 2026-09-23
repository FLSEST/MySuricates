package com.automation.service.ai;

import java.util.List;

/**
 * Was ein Mensch braucht, um einen KI-Anbieter auf seinem Rechner einzurichten — gelesen vom
 * Installer ({@code scripts/lib/provider-catalog.generated.sh}, erzeugt von ProviderCatalogTest).
 * So nennt der Installer alle Anbieter gleichrangig, statt Claude fest zu verdrahten (2255f2ee).
 * Dieselben Angaben speisen die Statusanzeige der Oberflaeche ({@code AiProviderStatusService},
 * f5a273ae): Programm gefunden, Anmeldung gefunden, sonst der Befehl.
 *
 * @param cli         Programmname auf dem PATH
 * @param install     Installationsbefehl bzw. -weg
 * @param login       wie man sich anmeldet
 * @param loginFiles  Dateien relativ zu {@code $HOME}, deren Vorhandensein eine Anmeldung anzeigt;
 *                    {@code datei:muster} heisst, die Datei muss das Muster enthalten. Eine Heuristik:
 *                    Installer und Statusanzeige sehen, dass eine Anmeldung da ist, nicht, ob sie gilt.
 * @param loginEnv    Umgebungsvariablen, von denen eine gesetzte (nicht leere) ebenfalls als Anmeldung
 *                    zaehlt, etwa ein API-Schluessel oder OAuth-Token (0b1a3ae7). Geprueft wird nur,
 *                    ob sie gesetzt ist; der Wert wird nie ausgegeben.
 * @param loginDir    Verzeichnis relativ zu {@code $HOME}, in dem die CLI ihre Anmeldung ablegt und das
 *                    {@code loginDirEnv} umlenkt; null, wenn keine Variable es umlenkt
 * @param loginDirEnv Umgebungsvariable, die {@code loginDir} umlenkt ({@code CODEX_HOME} …). Container-
 *                    Instanzen setzen sie (scripts/lib/host-mounts.sh), die Statusanzeige sucht
 *                    Anmeldedateien unter {@code loginDir} dann dort. Der Installer liest beides nicht:
 *                    er laeuft auf dem Rechner, wo die Verzeichnisse an ihrem Platz liegen.
 */
public record ProviderSetup(String cli, String install, String login, List<String> loginFiles,
                            List<String> loginEnv, String loginDir, String loginDirEnv) {
    public ProviderSetup {
        loginFiles = List.copyOf(loginFiles);
        loginEnv = List.copyOf(loginEnv);
    }

    /** Ein Anbieter ohne umlenkbares Anmeldeverzeichnis. */
    public ProviderSetup(String cli, String install, String login, List<String> loginFiles, List<String> loginEnv) {
        this(cli, install, login, loginFiles, loginEnv, null, null);
    }
}

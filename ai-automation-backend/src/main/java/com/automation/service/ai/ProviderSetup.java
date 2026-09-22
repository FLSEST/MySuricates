package com.automation.service.ai;

import java.util.List;

/**
 * Was ein Mensch braucht, um einen KI-Anbieter auf seinem Rechner einzurichten — gelesen vom
 * Installer ({@code scripts/lib/provider-catalog.generated.sh}, erzeugt von ProviderCatalogTest).
 * So nennt der Installer alle Anbieter gleichrangig, statt Claude fest zu verdrahten (2255f2ee).
 *
 * @param cli        Programmname auf dem PATH
 * @param install    Installationsbefehl bzw. -weg
 * @param login      wie man sich anmeldet
 * @param loginFiles Dateien relativ zu {@code $HOME}, deren Vorhandensein eine Anmeldung anzeigt;
 *                   {@code datei:muster} heisst, die Datei muss das Muster enthalten. Heuristik fuer
 *                   den Installer — das Backend selbst prueft keine Anmeldung.
 * @param loginEnv   Umgebungsvariablen, von denen eine gesetzte (nicht leere) ebenfalls als Anmeldung
 *                   zaehlt, etwa ein API-Schluessel oder OAuth-Token (0b1a3ae7). Der Installer prueft
 *                   nur, ob sie gesetzt ist, und gibt den Wert nie aus.
 */
public record ProviderSetup(String cli, String install, String login, List<String> loginFiles,
                            List<String> loginEnv) {
    public ProviderSetup {
        loginFiles = List.copyOf(loginFiles);
        loginEnv = List.copyOf(loginEnv);
    }
}

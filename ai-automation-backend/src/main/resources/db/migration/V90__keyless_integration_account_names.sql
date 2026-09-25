-- RSS/Atom und Open-Meteo speicherten beim Verbinden einen fest deutschen Kontonamen
-- („… (ohne Zugangsdaten)“), den die Integrationsseite in jeder Sprache zeigte (TODO 6e1e87c2).
-- Neue Verbindungen heißen nur nach dem Dienst, wie der iCal-Feed; bestehende ziehen hier nach.
UPDATE integration_connections SET account_info = '{"name":"RSS/Atom"}'
 WHERE provider_id = 'feed' AND account_info = '{"name":"RSS/Atom (ohne Zugangsdaten)"}';
UPDATE integration_connections SET account_info = '{"name":"Open-Meteo"}'
 WHERE provider_id = 'open-meteo' AND account_info = '{"name":"Open-Meteo (ohne Zugangsdaten)"}';

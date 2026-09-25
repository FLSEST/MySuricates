-- Seiten-Vorschläge des Assistenten je Seite und Sprache (TODO 5157fac2): Bisher gab es je Seite
-- einen Satz, erzeugt auf Deutsch, den auch die englische Oberfläche zeigte. SQLite kann die
-- UNIQUE-Bedingung auf page nicht entfernen, deshalb wird die Tabelle neu gebaut. Der Bestand gilt
-- als deutsch. Keine Fremdschlüssel verweisen auf page_suggestions.

CREATE TABLE page_suggestions_new (
    id                  TEXT PRIMARY KEY,
    page                TEXT NOT NULL,
    locale              TEXT NOT NULL DEFAULT 'de',
    suggestions         TEXT NOT NULL DEFAULT '[]',
    generated_by        TEXT NOT NULL DEFAULT 'CLAUDE',
    generated_at        REAL NOT NULL,
    based_on_sessions   INTEGER NOT NULL DEFAULT 0,
    UNIQUE (page, locale)
);

INSERT INTO page_suggestions_new (id, page, locale, suggestions, generated_by, generated_at, based_on_sessions)
SELECT id, page, 'de', suggestions, generated_by, generated_at, based_on_sessions FROM page_suggestions;

DROP TABLE page_suggestions;
ALTER TABLE page_suggestions_new RENAME TO page_suggestions;

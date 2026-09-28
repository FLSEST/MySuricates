-- Ziele Stufe 2: „Entschieden“ als sichtbarer Zustand am TODO
-- (TODO 68a5e24e Ziele Stufe 2: „Entschieden“ als sichtbarer Zustand am TODO (❓/✔, Zähler je Teilziel und Projekt)).
--
-- Neben der offenen Frage (open_question, V85) haelt ein TODO jetzt die Entscheidung fest, die sie
-- beantwortet hat: den Text, wann und von wem (USER/AI wie rated_by). Eine Entscheidung leert die
-- offene Frage; eine spaetere neue Frage laesst die Entscheidung stehen. Der Zustand
-- (QUESTION/DECIDED/NONE) wird beim Lesen berechnet, nicht gespeichert.
ALTER TABLE todos ADD COLUMN decision TEXT;
ALTER TABLE todos ADD COLUMN decided_at TEXT;
ALTER TABLE todos ADD COLUMN decided_by TEXT;

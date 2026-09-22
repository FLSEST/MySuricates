-- Blaupausen als eigene Art neben Vorlagen (TODO 268ae0db).
--
-- Blaupausen sind leere Gerueste mit lauffaehigen Platzhaltern, die zeigen, wie sich Block-Typen
-- verknuepfen lassen. Sie liegen unter templates/blueprints/ und teilen Tabelle, Loader und Gates
-- mit den Vorlagen; kind trennt sie, damit die Vorlagen-Galerie einen eigenen Reiter fuellen kann.
-- Bestehende Zeilen sind Vorlagen.
ALTER TABLE workflow_templates ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEMPLATE';

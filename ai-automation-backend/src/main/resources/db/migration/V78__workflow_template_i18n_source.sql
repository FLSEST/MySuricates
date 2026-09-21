-- Zweisprachige Schritte in Vorlagen (TODO a129a661).
--
-- Name und Beschreibung einer Vorlage sind schon zweisprachig ({"de": …, "en": …}). Seit dieser
-- Migration duerfen markierte Vorlagen ("i18n": ["de","en"]) dieselbe Form auch in blocks und
-- instructions tragen. Die Spalte blocks behaelt die englisch aufgeloeste Fassung, damit alle
-- heutigen Leser unveraendert funktionieren; i18n_source haelt die unaufgeloesten blocks und
-- instructions mit Marker. GET /workflow-templates?locale= loest daraus beim Lesen auf.
-- NULL = einsprachige Vorlage (alle bisherigen).
ALTER TABLE workflow_templates ADD COLUMN i18n_source TEXT;

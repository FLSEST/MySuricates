-- Onboarding profiles become bilingual (TODO a805d0a7): the content is a JSON map {de, en} like
-- name and description. Everything stored so far — built-in and custom profiles, and their
-- history — was written in German.
UPDATE onboarding_profiles SET content = json_object('de', content);
UPDATE onboarding_profile_history SET content = json_object('de', content);

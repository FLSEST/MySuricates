ALTER TABLE capabilities ADD COLUMN writes INTEGER NOT NULL DEFAULT 0;

-- Mark known write-capable built-in capabilities. Keeps in sync with BuiltInSkills.capWrites(...).
UPDATE capabilities SET writes = 1
WHERE skill_id = 'builtin-test-generator'
  AND name IN ('generate_unit_tests', 'generate_integration_tests');

UPDATE capabilities SET writes = 1
WHERE skill_id = 'builtin-feature-implementer'
  AND name IN ('implement_feature', 'write_tests', 'apply_routes_glue');

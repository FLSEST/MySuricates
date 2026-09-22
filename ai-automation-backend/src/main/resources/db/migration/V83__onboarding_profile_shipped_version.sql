-- Edited built-in onboarding profiles (TODO 644fd5ad): `customized` marks a profile whose text was
-- changed by hand, `shipped_version` the shipped text it is based on. A newer shipped text then
-- replaces an unchanged profile and is offered for an edited one ("update available") instead of
-- being skipped or overwritten. 0 = unknown (from before V83): the seeder decides once by comparing
-- the stored text with the shipped one.
ALTER TABLE onboarding_profiles ADD COLUMN customized INTEGER NOT NULL DEFAULT 0;
ALTER TABLE onboarding_profiles ADD COLUMN shipped_version INTEGER NOT NULL DEFAULT 0;

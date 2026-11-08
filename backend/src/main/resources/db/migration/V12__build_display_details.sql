-- Public build artwork and hover details. Empty objects mark a completed fetch with no optional details.
ALTER TABLE build_specializations ADD COLUMN display_details JSONB;
ALTER TABLE build_traits ADD COLUMN display_details JSONB;
ALTER TABLE build_skills ADD COLUMN display_details JSONB;
ALTER TABLE build_pets ADD COLUMN display_details JSONB;

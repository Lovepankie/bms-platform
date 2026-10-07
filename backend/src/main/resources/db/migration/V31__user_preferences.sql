-- V31: a small per-user preference document (issue #19, ADR-025).
--
-- Additive only (chapter 6 section 6.9): one column on users with a default, so every existing row
-- is valid and no running code reads it yet. The document holds what a user chose for themselves
-- and nothing a permission depends on. Today it holds one key, "tours": per guided tour id, the
-- tour version the user finished or chose not to see again, so a first-run tour follows the user
-- across devices. The identity module validates the shape before it writes; the row level
-- security and grants of users already cover the column.

ALTER TABLE users ADD COLUMN preferences jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE users ADD CONSTRAINT users_preferences_object CHECK (jsonb_typeof(preferences) = 'object');

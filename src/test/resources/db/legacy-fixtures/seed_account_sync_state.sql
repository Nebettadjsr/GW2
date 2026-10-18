-- Unscoped per-source completion markers: they name no account, so they prove nothing about one.
INSERT INTO account_sync_state (source, fetched_at) VALUES
  ('BANK',            '2026-02-01T10:00:00Z'),
  ('MATERIALS',       '2026-02-02T11:00:00Z'),
  ('ACCOUNT_RECIPES', '2026-02-03T12:00:00Z'),
  ('CHARACTERS',      '2026-02-04T13:00:00Z'),
  ('LUCK',            '2026-02-05T14:00:00Z');

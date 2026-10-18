-- Identity-aware legacy Luck rows: one provably owned GUID, one malformed identity, and one GUID
-- claimed twice in different letter case so the claim conflicts.
INSERT INTO account_luck (account_id, consumed_luck, fetched_at) VALUES
  ('11111111-2222-3333-4444-555555555555', 1234567, '2026-02-05T14:00:00Z'),
  ('not-a-guid',                            99,      '2026-02-05T14:00:00Z'),
  ('AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE',  7,       '2026-02-05T14:00:00Z'),
  ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',  8,       '2026-02-05T14:00:00Z');

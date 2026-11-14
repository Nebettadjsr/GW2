-- STORY-DELETE-001: every direct account-owned foreign key to account_registry(account_id) cascades,
-- so deleting the account root deletes the account's cached data and nothing shared. Character
-- descendants already cascade through their account-scoped parents.
DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN
        SELECT c.conrelid::regclass AS tbl, c.conname
        FROM pg_constraint c
        WHERE c.contype = 'f'
          AND c.confrelid = 'account_registry'::regclass
          AND c.confdeltype <> 'c'
          AND c.connamespace = current_schema()::regnamespace
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.tbl, fk.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (account_id) '
                       'REFERENCES account_registry (account_id) ON DELETE CASCADE', fk.tbl, fk.conname);
    END LOOP;
END $$;

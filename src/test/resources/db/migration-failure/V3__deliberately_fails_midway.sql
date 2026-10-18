-- Test-only migration, deliberately not part of the production chain
-- (classpath:db/migration). It proves that a PostgreSQL migration in this project's Flyway
-- configuration is one transaction: the table below must not survive the failure on the next
-- statement, and version 3 must not appear as applied.
CREATE TABLE should_not_survive_a_failed_migration (id INTEGER PRIMARY KEY);

INSERT INTO should_not_survive_a_failed_migration (id) VALUES (1);

-- Fails: the column does not exist. Everything above must roll back with it.
ALTER TABLE gw2_account ADD CONSTRAINT deliberate_failure CHECK (no_such_column > 0);

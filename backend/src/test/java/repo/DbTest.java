package repo;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for the shared connection
 * helper itself (STORY-INFRA-003). Every other repository/sync integration test seeds its own
 * disposable schema via a direct {@code DriverManager.getConnection} call (see
 * {@link CharacterSelectionCraftingPlanIntegrationTest}'s Javadoc) rather than through
 * {@link Db#open()}, so none of them actually exercise the helper both {@code repo} and
 * {@code sync} callers now share. This test opens and closes a real connection through
 * {@link Db#open()} against the developer's configured database instead.
 */
class DbTest {

    @Test
    void open_returnsUsableConnection_andCloseLeavesItClosed() throws Exception {
        Connection con = Db.open();
        try {
            assertFalse(con.isClosed());
            assertTrue(con.isValid(5));
        } finally {
            con.close();
        }
        assertTrue(con.isClosed());
    }
}

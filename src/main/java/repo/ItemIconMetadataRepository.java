package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * The retained icon-source metadata of one item ({@code items.icon_url}), read only when the image
 * cache misses (TARGET_ARCHITECTURE.md §12.1 explicitly allows this local read and forbids it from
 * triggering GW2 JSON acquisition).
 *
 * <p>Deliberately not part of {@link ItemRepository}: that repository's batch read serves page data,
 * where a per-row lookup is forbidden. This one is a single-row read on a path that is already about
 * to do disk and possibly network work, and no page-data read calls it.
 */
public class ItemIconMetadataRepository {

    /** Reads one item's retained source using the shared {@link Db#open()} connection configuration. */
    public Optional<String> findIconSource(int itemId) throws SQLException {
        try (Connection con = Db.open()) {
            return findIconSource(con, itemId);
        }
    }

    /**
     * Same read against a caller-supplied connection, so an integration test can point it at a
     * disposable schema (docs/TEST_STRATEGY.md §31.2).
     *
     * @return empty when the item is unknown or holds no metadata; never a blank string
     */
    public Optional<String> findIconSource(Connection con, int itemId) throws SQLException {
        String sql = """
            SELECT icon_url
            FROM items
            WHERE item_id = ?
        """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, itemId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();

                String iconUrl = rs.getString("icon_url");
                return iconUrl == null || iconUrl.isBlank() ? Optional.empty() : Optional.of(iconUrl);
            }
        }
    }
}

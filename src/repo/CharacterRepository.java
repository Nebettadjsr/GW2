package repo;

import java.sql.*;
import java.util.*;

public class CharacterRepository {

    public static class DiscRow {
        public final String charName;
        public final String discipline;
        public final int rating;
        public final boolean active;

        public DiscRow(String charName, String discipline, int rating, boolean active) {
            this.charName = charName;
            this.discipline = discipline;
            this.rating = rating;
            this.active = active;
        }
    }

    public List<DiscRow> loadAllCharacterCrafting() throws SQLException {
        String sql = """
            SELECT c.name AS char_name, cc.discipline, cc.rating, cc.is_active
            FROM character_crafting cc
            JOIN characters c ON c.character_id = cc.character_id
            ORDER BY cc.discipline, cc.rating DESC, c.name
        """;

        List<DiscRow> out = new ArrayList<>();
        try (Connection con = Db.open();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(new DiscRow(
                        rs.getString("char_name"),
                        rs.getString("discipline"),
                        rs.getInt("rating"),
                        rs.getBoolean("is_active")
                ));
            }
        }
        return out;
    }
}
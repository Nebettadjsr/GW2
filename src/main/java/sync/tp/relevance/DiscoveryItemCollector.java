package sync.tp.relevance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

public final class DiscoveryItemCollector {

    private static final String SQL = """
          WITH excluded AS (
            SELECT recipe_id FROM account_recipes
            UNION
            SELECT DISTINCT recipe_id FROM character_recipes
          ),
          discoverable AS (
            SELECT r.recipe_id
            FROM recipes r
            LEFT JOIN excluded e ON e.recipe_id = r.recipe_id
            WHERE e.recipe_id IS NULL
          ),
          relevant_items AS (
            SELECT r.output_item_id AS item_id
            FROM recipes r
            JOIN discoverable d ON d.recipe_id = r.recipe_id
            WHERE r.output_item_id IS NOT NULL
          
            UNION
          
            SELECT ri.item_id
            FROM recipe_ingredients ri
            JOIN discoverable d ON d.recipe_id = ri.recipe_id
            WHERE ri.item_id IS NOT NULL
          )
          SELECT DISTINCT i.item_id
        FROM relevant_items i
        JOIN tp_tradeable_items t ON t.item_id = i.item_id
        LEFT JOIN tp_prices tp ON tp.item_id = i.item_id
        WHERE tp.item_id IS NULL
           OR tp.fetched_at IS NULL
           OR tp.fetched_at < (now() - interval '10 minutes')
        """;

    public Set<Integer> collect(Connection con) throws Exception {
        Set<Integer> itemIds = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) itemIds.add(rs.getInt(1));
        }
        return itemIds;
    }
}
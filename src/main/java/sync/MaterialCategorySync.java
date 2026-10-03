package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import repo.Db;
import repo.MaterialStorageSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persists ArenaNet's ordered material-category and item-position catalog. */
public final class MaterialCategorySync {
    private MaterialCategorySync() {}

    record Category(int id, String name, int displayOrder, List<Integer> itemIds) {}

    public static void syncMaterialCategories() throws Exception {
        JsonNode root = Gw2ApiClient.getPublicArray("https://api.guildwars2.com/v2/materials?ids=all");
        List<Category> categories = parse(root);
        try (Connection con = Db.open()) {
            MaterialStorageSchema.ensure(con);
            con.setAutoCommit(false);
            try {
                persist(con, categories);
                con.commit();
            } catch (Exception failure) {
                con.rollback();
                throw failure;
            }
        }
    }

    static List<Category> parse(JsonNode root) {
        List<Category> categories = new ArrayList<>();
        for (JsonNode node : root) {
            List<Integer> ids = new ArrayList<>();
            for (JsonNode id : node.path("items")) ids.add(id.intValue());
            categories.add(new Category(node.path("id").intValue(), node.path("name").asText(),
                    node.path("order").intValue(), List.copyOf(ids)));
        }
        return categories;
    }

    static void persist(Connection con, List<Category> categories) throws SQLException {
        try (PreparedStatement category = con.prepareStatement("""
                     INSERT INTO material_categories (category_id, name, display_order)
                     VALUES (?, ?, ?)
                     ON CONFLICT (category_id) DO UPDATE SET name = EXCLUDED.name,
                         display_order = EXCLUDED.display_order
                     """);
             PreparedStatement item = con.prepareStatement("""
                     INSERT INTO material_category_items (category_id, position, item_id)
                     VALUES (?, ?, ?) ON CONFLICT (category_id, position) DO UPDATE SET item_id = EXCLUDED.item_id
                     """)) {
            Set<Integer> seen = new HashSet<>();
            for (Category value : categories) {
                category.setInt(1, value.id()); category.setString(2, value.name());
                category.setInt(3, value.displayOrder()); category.addBatch();
                seen.add(value.id());
                for (int position = 0; position < value.itemIds().size(); position++) {
                    item.setInt(1, value.id()); item.setInt(2, position);
                    item.setInt(3, value.itemIds().get(position)); item.addBatch();
                }
            }
            category.executeBatch();
            item.executeBatch();
            if (seen.isEmpty()) throw new SQLException("Material catalog contained no categories");
            // Remove stale positions/categories only after the complete new catalog is written.
            try (PreparedStatement trim = con.prepareStatement("DELETE FROM material_category_items WHERE category_id = ? AND position >= ?")) {
                for (Category value : categories) {
                    trim.setInt(1, value.id()); trim.setInt(2, value.itemIds().size()); trim.addBatch();
                }
                trim.executeBatch();
            }
            try (PreparedStatement delete = con.prepareStatement("DELETE FROM material_categories WHERE category_id <> ALL (?)")) {
                delete.setArray(1, con.createArrayOf("int", seen.toArray()));
                delete.executeUpdate();
            }
            try (PreparedStatement clear = con.prepareStatement("DELETE FROM material_category_items WHERE category_id NOT IN (SELECT category_id FROM material_categories)")) {
                clear.executeUpdate();
            }
        }
    }
}

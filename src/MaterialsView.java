import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.sql.*;
import java.util.*;

public class MaterialsView {

    // TODO: später sauber zentralisieren (repo.AppConfig), für jetzt hier wie bei BankView:
    private static final String DB_URL  = "jdbc:postgresql://localhost:5432/GWDatabase";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "0";

    // --- Public entry ---
    public static void show(Stage stage, Runnable onBack) {
        Button btnBack = new Button("← Back");
        btnBack.setOnAction(e -> onBack.run());

        HBox topBar = new HBox(btnBack);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        Parent content = buildMaterialsContent();

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(content);
        root.setStyle("-fx-background-color: #0f1115;");

        stage.setScene(new Scene(root, 1000, 800));
    }

    // --- UI builder ---
    private static Parent buildMaterialsContent() {
        // VBox: viele “Blöcke” untereinander (wie im Spiel)
        VBox blocks = new VBox(22);
        blocks.setPadding(new Insets(12));

        // Daten aus DB laden: Map<KategorieName, Slots>
        LinkedHashMap<String, List<MatEntry>> grouped = loadMaterialsGrouped();

        for (Map.Entry<String, List<MatEntry>> e : grouped.entrySet()) {
            String categoryName = e.getKey();
            List<MatEntry> mats = e.getValue();

            Label header = new Label(categoryName);
            header.setStyle("""
                -fx-text-fill: white;
                -fx-font-size: 22px;
                -fx-font-weight: bold;
            """);

            GridPane grid = buildGrid(mats, 10);

            VBox block = new VBox(10, header, grid);
            block.setPadding(new Insets(8, 8, 18, 8));

            // optional: leichte Trennung
            block.setStyle("-fx-background-color: rgba(255,255,255,0.02); -fx-background-radius: 12;");

            blocks.getChildren().add(block);
        }

        ScrollPane sp = new ScrollPane(blocks);
        sp.setFitToWidth(true);
        sp.setPannable(true);
        sp.setStyle("-fx-background: #0f1115; -fx-background-color: #0f1115;");

        return sp;
    }

    private static GridPane buildGrid(List<MatEntry> mats, int cols) {
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);

        // gleich breite Spalten (GW-Look)
        for (int c = 0; c < cols; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPrefWidth(68);
            grid.getColumnConstraints().add(cc);
        }

        for (int i = 0; i < mats.size(); i++) {
            int row = i / cols;
            int col = i % cols;

            MatEntry m = mats.get(i);
            grid.add(createTile(m.iconPath, m.count, m.rarity), col, row);
        }

        return grid;
    }

    // --- DB load (grouped) ---
    private static LinkedHashMap<String, List<MatEntry>> loadMaterialsGrouped() {
        // Kategorien “wie im Spiel”: Wir mappen category-id -> Name.
        // Später können wir das automatisch aus /v2/materials/categories holen, aber erstmal fix.
        Map<Integer, String> catName = materialCategoryNames();

        // Ergebnis: in stabiler Reihenfolge (LinkedHashMap)
        LinkedHashMap<String, List<MatEntry>> out = new LinkedHashMap<>();
        for (String name : catName.values()) out.put(name, new ArrayList<>());

        // Falls doch Kategorien kommen, die wir nicht kennen:
        final String UNKNOWN_PREFIX = "Category ";

        String sql = """
            SELECT am.category, am.item_id, am.count,
                   i.icon_path, i.rarity
            FROM account_materials am
            LEFT JOIN items i ON i.item_id = am.item_id
            WHERE am.count IS NOT NULL AND am.count > 0
            ORDER BY am.category, am.item_id
        """;

        try (Connection con = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int category = rs.getInt("category");
                int count = rs.getInt("count");
                String iconPath = rs.getString("icon_path");
                String rarity = rs.getString("rarity");

                String group = catName.getOrDefault(category, UNKNOWN_PREFIX + category);

                out.computeIfAbsent(group, k -> new ArrayList<>());
                out.get(group).add(new MatEntry(iconPath, count, rarity));
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Leere Gruppen entfernen
        out.entrySet().removeIf(e -> e.getValue().isEmpty());

        return out;
    }

    // --- Tile (GW style: icon + stack number, rarity border) ---
    private static StackPane createTile(String iconPath, int count, String rarity) {
        int size = 64;

        StackPane tile = new StackPane();
        tile.setPrefSize(size, size);

        // default background
        String border = rarityColor(rarity);
        tile.setStyle("-fx-background-color: #1b1b1b; -fx-border-width: 2; -fx-border-color: " + border + ";");

        if (iconPath == null || iconPath.isBlank()) {
            tile.setStyle("-fx-background-color: #2a2a2a; -fx-border-width: 2; -fx-border-color: #4a4a4a;");
            return tile;
        }

        String fxUrl = "file:" + iconPath.replace("\\", "/");
        ImageView icon = new ImageView(new Image(fxUrl, true));
        icon.setFitWidth(size);
        icon.setFitHeight(size);
        icon.setPreserveRatio(true);

        Label countLbl = new Label(count > 1 ? String.valueOf(count) : "");
        StackPane.setAlignment(countLbl, Pos.TOP_LEFT);
        countLbl.setStyle("""
            -fx-text-fill: white;
            -fx-font-weight: bold;
            -fx-padding: 1 4 1 4;
            -fx-background-color: rgba(0,0,0,0.65);
        """);

        tile.getChildren().addAll(icon, countLbl);
        return tile;
    }

    private static String rarityColor(String rarity) {
        if (rarity == null) return "#888888";
        return switch (rarity.toLowerCase()) {
            case "junk"       -> "#AAAAAA";
            case "basic"      -> "#FFFFFF";
            case "fine"       -> "#4aa3ff";
            case "masterwork" -> "#2ecc71";
            case "rare"       -> "#f1c40f";
            case "exotic"     -> "#f39c12";
            case "ascended"   -> "#ff4fa3";
            case "legendary"  -> "#b36bff";
            default           -> "#888888";
        };
    }

    private static Map<Integer, String> materialCategoryNames() {
        // Wichtig: Die IDs können bei ArenaNet so sein – wenn deine DB andere category-IDs liefert,
        // sehen wir’s sofort (dann tauchen “Category 12” etc. auf).
        LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
        m.put(1,  "Basic Crafting Materials");
        m.put(2,  "Intermediate Crafting Materials");
        m.put(3,  "Advanced Crafting Materials");
        m.put(4,  "Ascended Materials");
        m.put(5,  "Cooking Materials");
        m.put(6,  "Cooking Ingredients");
        m.put(7,  "Scribing Materials");
        m.put(8,  "Festive Materials");
        m.put(9,  "Guild Materials");
        m.put(10, "Other");
        return m;
    }

    // --- Tiny DTO ---
    private static class MatEntry {
        final String iconPath;
        final int count;
        final String rarity;

        MatEntry(String iconPath, int count, String rarity) {
            this.iconPath = iconPath;
            this.count = count;
            this.rarity = rarity;
        }
    }
}

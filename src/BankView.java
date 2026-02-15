import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.layout.Region; // add this import

public class BankView {

    private static final String DB_URL  = "jdbc:postgresql://localhost:5432/GWDatabase";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "0";

    // --- simple model for slot-based bank data ---
    public record BankSlot(int slot, Integer itemId, Integer count, String iconPath, String rarity) {}

    public static void show(Stage stage, Runnable onBack) {

        Button btnBack = new Button("← Back");
        btnBack.setOnAction(e -> onBack.run());

        Label title = new Label("Bank");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");

        HBox topBar = new HBox(10, btnBack, title);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: #0b0d12;");

        Parent centerContent;
        try (Connection con = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            List<BankSlot> slots = loadBankSlots(con);
            centerContent = buildBankGrid(slots);
        } catch (Exception ex) {
            ex.printStackTrace();
            Label err = new Label("DB error: " + ex.getMessage());
            err.setStyle("-fx-text-fill: red;");
            centerContent = new StackPane(err);
        }

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        BorderPane.setAlignment(centerContent, Pos.CENTER);
        root.setCenter(centerContent);

        root.setStyle("-fx-background-color: #0f1115;");

        Scene scene = new Scene(root, 1280, 720);
        stage.setScene(scene);
        stage.show();
    }

    private static List<BankSlot> loadBankSlots(Connection con) throws SQLException {
        String sql = """
            SELECT b.slot, b.item_id, b.count, i.icon_path, i.rarity
            FROM account_bank b
            LEFT JOIN items i ON i.item_id = b.item_id
            ORDER BY b.slot
        """;

        List<BankSlot> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int slot = rs.getInt("slot");
                Integer itemId  = (Integer) rs.getObject("item_id");
                Integer count   = (Integer) rs.getObject("count");
                String iconPath = rs.getString("icon_path");
                String rarity   = rs.getString("rarity");

                out.add(new BankSlot(slot, itemId, count, iconPath, rarity));
            }
        }
        return out;
    }

    private static Parent buildBankGrid(List<BankSlot> slots) {
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));

        // 10 columns
        for (int c = 0; c < 10; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPrefWidth(68);
            grid.getColumnConstraints().add(cc);
        }
        grid.setMaxWidth(Region.USE_PREF_SIZE);
        grid.setMinWidth(Region.USE_PREF_SIZE);
        grid.setAlignment(Pos.TOP_CENTER);


        Map<Integer, BankSlot> bySlot = new HashMap<>();
        int maxSlot = -1;
        for (BankSlot s : slots) {
            bySlot.put(s.slot(), s);
            if (s.slot() > maxSlot) maxSlot = s.slot();
        }

        int totalSlots = maxSlot + 1; // 0..maxSlot

        for (int slot = 0; slot < totalSlots; slot++) {
            int block  = slot / 30;
            int within = slot % 30;
            int row    = within / 10; // 0..2
            int col    = within % 10; // 0..9

            int gridRow = block * 4 + row; // 3 rows + spacer row
            ensureSpacerRow(grid, block * 4 + 3);

            BankSlot data = bySlot.get(slot);
            Node tile = createSlotTile(data);
            grid.add(tile, col, gridRow);
        }

        StackPane centered = new StackPane(grid);
        centered.setAlignment(Pos.TOP_CENTER);
        centered.setPadding(new Insets(12));

        ScrollPane sp = new ScrollPane(centered);
        sp.setPannable(true);
        sp.setFitToWidth(true);   // IMPORTANT: content expands to viewport width
        sp.setStyle("-fx-background: #0f1115; -fx-background-color: #0f1115;");

        return sp;

    }

    private static void ensureSpacerRow(GridPane grid, int spacerRowIndex) {
        while (grid.getRowConstraints().size() <= spacerRowIndex) {
            grid.getRowConstraints().add(new RowConstraints());
        }
        RowConstraints spacer = grid.getRowConstraints().get(spacerRowIndex);
        spacer.setMinHeight(18);
        spacer.setPrefHeight(18);
    }

    private static Node createSlotTile(BankSlot s) {
        int size = 64;

        StackPane tile = new StackPane();
        tile.setPrefSize(size, size);

        if (s == null || s.itemId() == null || s.iconPath() == null) {
            // empty slot
            tile.setStyle("-fx-background-color: #2a2a2a; -fx-border-width: 2; -fx-border-color: #4a4a4a;");
            return tile;
        }

        String fxUrl = "file:" + s.iconPath().replace("\\", "/");
        ImageView icon = new ImageView(new Image(fxUrl, true));
        icon.setFitWidth(size);
        icon.setFitHeight(size);
        icon.setPreserveRatio(true);

        int count = (s.count() == null ? 0 : s.count());
        Label countLbl = new Label(count > 1 ? String.valueOf(count) : "");
        StackPane.setAlignment(countLbl, Pos.TOP_LEFT);
        countLbl.setStyle("""
            -fx-text-fill: white;
            -fx-font-weight: bold;
            -fx-padding: 1 4 1 4;
            -fx-background-color: rgba(0,0,0,0.65);
        """);

        String border = rarityColor(s.rarity());
        tile.setStyle("-fx-background-color: #1b1b1b; -fx-border-width: 2; -fx-border-color: " + border + ";");

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
}
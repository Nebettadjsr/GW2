import application.MaterialStorageService;
import application.MaterialStorageService.MaterialCategory;
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
import repo.MaterialStorageRepository.MaterialStorageRow;

import java.util.*;

public class MaterialsView {

    // --- Public entry ---
    public static void show(Stage stage, Runnable onBack) {
        show(stage, onBack, new MaterialStorageService());
    }

    /** Overload used by UI verification to inject a controlled {@link MaterialStorageService} (STORY-APP-009). */
    public static void show(Stage stage, Runnable onBack, MaterialStorageService materialStorageService) {
        Button btnBack = new Button("← Back");
        btnBack.setOnAction(e -> onBack.run());

        HBox topBar = new HBox(btnBack);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        Parent content = buildMaterialsContent(materialStorageService);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(content);
        root.setStyle("-fx-background-color: #0f1115;");

        stage.setScene(new Scene(root, 1280, 720));
    }

    // --- UI builder ---
    private static Parent buildMaterialsContent(MaterialStorageService materialStorageService) {
        // VBox: viele “Blöcke” untereinander (wie im Spiel)
        VBox blocks = new VBox(22);
        blocks.setId("materialsBlocks");
        blocks.setPadding(new Insets(12));
        blocks.setMaxWidth(Region.USE_PREF_SIZE);
        blocks.setAlignment(Pos.TOP_CENTER);


        // Daten über den Application Service laden: Kategorien mit ihren Slots
        List<MaterialCategory> grouped = loadMaterialsGrouped(materialStorageService);

        for (MaterialCategory e : grouped) {
            String categoryName = e.name();
            List<MaterialStorageRow> mats = e.materials();

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

        StackPane centered = new StackPane(blocks);
        centered.setAlignment(Pos.TOP_CENTER);
        centered.setPadding(new Insets(10));

        ScrollPane sp = new ScrollPane(centered);
        sp.setFitToWidth(true);   // viewport fills width
        sp.setPannable(true);
        sp.setStyle("-fx-background: #0f1115; -fx-background-color: #0f1115;");

        return sp;

    }

    private static GridPane buildGrid(List<MaterialStorageRow> mats, int cols) {
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

            MaterialStorageRow m = mats.get(i);
            grid.add(createTile(m.iconPath(), m.count(), m.rarity()), col, row);
        }

        return grid;
    }

    // --- Load (grouped) via the application service ---
    private static List<MaterialCategory> loadMaterialsGrouped(MaterialStorageService materialStorageService) {
        try {
            return materialStorageService.getMaterialStorage();
        } catch (Exception ex) {
            // Unchanged failure presentation: log the stack trace and render an empty page - this
            // view has never shown a load error to the user (STORY-APP-009).
            ex.printStackTrace();
            return List.of();
        }
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
        StackPane.setAlignment(countLbl, Pos.CENTER);
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

}
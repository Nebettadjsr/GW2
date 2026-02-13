import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.Optional;

public class Gw2App extends Application {

    private Stage stage;

    private Scene homeScene;
    private Scene matsScene;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        homeScene = createHomeScene();
        matsScene = createPlaceholderScene("Materials (placeholder)", () -> stage.setScene(homeScene));

        stage.setTitle("Nebet's GW2 Fan Tool");
        stage.setScene(homeScene);
        stage.show();
    }

    private Scene createHomeScene() {

        // Logo + Title row
        Image logo = new Image(getClass().getResource("/images/logo.png").toExternalForm());
        ImageView logoView = new ImageView(logo);
        logoView.fitHeightProperty();
        logoView.setPreserveRatio(true);

        Label titleText = new Label("Nebet’s Fan Tool");
        titleText.setFont(Font.font("System", FontWeight.BOLD, 24));
        titleText.setStyle("-fx-text-fill: white;");

        HBox titleBox = new HBox(10, logoView, titleText);
        titleBox.setAlignment(Pos.CENTER);

        Label subtitle = new Label("Choose what you want to view");
        subtitle.setStyle("-fx-text-fill: white; -fx-opacity: 0.75;");

        // Buttons
        Button btnBank = new Button("See Bank");
        btnBank.setPrefWidth(180);
        btnBank.setStyle(buttonStyle());
        btnBank.setOnAction(e -> BankView.show(stage, () -> stage.setScene(homeScene)));

        Button btnMats = new Button("See Materials");
        btnMats.setPrefWidth(180);
        btnMats.setStyle(buttonStyle());
        btnMats.setOnAction(e ->
                                    MaterialsView.show(stage, () -> stage.setScene(homeScene)));

        Button btnEcto = new Button("Salvage Ecto for Dust & Luck Calculator");
        btnEcto.setPrefWidth(220);
        btnEcto.setOnAction(e -> EctoView.show(stage, () -> stage.setScene(homeScene)));
        btnEcto.setStyle(buttonStyle());

        Button btnCrafting = new Button("Crafting Profit");
        btnCrafting.setStyle(buttonStyle());
        btnCrafting.setOnAction(e ->
                                        CraftingProfitView.show(stage, () -> stage.setScene(homeScene)));

        // Refresh + status
        Label status = new Label("");
        status.setStyle("-fx-text-fill: #c9d1d9; -fx-opacity: 0.9;");

        Button btnRefresh = new Button("Refresh Account Data");
        btnRefresh.setPrefWidth(220);
        btnRefresh.setStyle(buttonStyle());

        Button btnGlobalRecipes = new Button("Sync ALL Recipes in GW");
        btnGlobalRecipes.setPrefWidth(220);
        btnGlobalRecipes.setStyle(buttonStyle());


        btnRefresh.setOnAction(e -> {
            btnRefresh.setDisable(true);
            status.setText("Refreshing account data...");

            Thread t = new Thread(() -> {
                try {
                    AccountRefreshService.refreshAll();

                    Platform.runLater(() -> {
                        status.setText("✅ Refresh finished.");
                        btnRefresh.setDisable(false);
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Refresh failed: " + ex.getMessage());
                        btnRefresh.setDisable(false);
                    });
                }
            });

            t.setDaemon(true);
            t.start();
        });

        btnGlobalRecipes.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("One-time global sync");
            a.setHeaderText("Sync ALL recipes from GW2 API into your DB?");
            a.setContentText("This can take a while and will write a lot of data.\n\nProceed?");
            Optional<ButtonType> result = a.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;

            btnGlobalRecipes.setDisable(true);
            status.setText("Syncing ALL recipes (global) ...");

            Thread t = new Thread(() -> {
                try {
                    Gw2DbSync.syncAllRecipesAndIngredientsGlobal();
                    Platform.runLater(() -> {
                        status.setText("✅ Global recipe sync finished.");
                        btnGlobalRecipes.setDisable(false);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Global recipe sync failed: " + ex.getMessage());
                        btnGlobalRecipes.setDisable(false);
                    });
                }
            });
            t.setDaemon(true);
            t.start();
        });


        HBox row1 = new HBox(12, btnBank, btnMats);
        row1.setAlignment(Pos.CENTER);

        HBox row2 = new HBox(12, btnEcto, btnRefresh);
        row2.setAlignment(Pos.CENTER);

// root:
        VBox root = new VBox(14, titleBox, subtitle, row1, row2, btnCrafting, btnGlobalRecipes, status);


        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #111318;");

        return new Scene(root, 640, 360);
    }

    private Scene createPlaceholderScene(String headerText, Runnable onBack) {
        Label header = new Label(headerText);
        header.setFont(Font.font("System", FontWeight.BOLD, 20));
        header.setStyle("-fx-text-fill: white;");

        Button back = new Button("← Back");
        back.setOnAction(e -> onBack.run());
        back.setStyle(buttonStyle());

        VBox root = new VBox(20, header, back);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #111318;");

        return new Scene(root, 800, 500);
    }

    private String buttonStyle() {
        return """
            -fx-background-color: #2a2f3a;
            -fx-text-fill: white;
            -fx-font-size: 14px;
            -fx-padding: 10 14 10 14;
            -fx-background-radius: 10;
        """;
    }

    public static void main(String[] args) {
        launch(args);
    }
}

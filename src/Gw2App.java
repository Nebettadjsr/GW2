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

import java.util.Objects;
import java.util.Optional;

import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import sync.AccountSync;
import sync.CharacterSync;
import sync.TpSync;


public class Gw2App extends Application {

    private Stage stage;

    private Scene homeScene;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
//        stage.getIcons().add(new Image(
//                Objects.requireNonNull(getClass().getResourceAsStream("/images/app-icon.png"))
//        ));

        homeScene = createHomeScene();
        homeScene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/styles/dark-scroll.css")).toExternalForm()
                                      );


        stage.setTitle("Nebet's GW2 Fan Tool");
        stage.setScene(homeScene);
        stage.show();
    }

    private Scene createHomeScene() {

        // Logo + Title row
        Image     logo     = new Image(getClass().getResource("/images/logo.png").toExternalForm());
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

        Label status = new Label("");
        status.setStyle("-fx-text-fill: #c9d1d9; -fx-opacity: 0.9;");


        // --------------------
// API Key placeholder row
// --------------------
        TextField apiKeyField = new TextField();
        apiKeyField.setPromptText("API Key (placeholder for now)");
        apiKeyField.setPrefWidth(420);

        Button btnSaveApiKey = new Button("Save");
        btnSaveApiKey.setStyle(buttonStyle());
        btnSaveApiKey.setOnAction(e -> {
            // placeholder only
            status.setText("ℹ️ API key saving not implemented yet.");
        });

        HBox apiRow = new HBox(10, apiKeyField, btnSaveApiKey);
        apiRow.setAlignment(Pos.CENTER);

// Spacers
        Region spacerSmall = new Region();
        spacerSmall.setMinHeight(10);

        Region spacerLarge1 = new Region();
        spacerLarge1.setMinHeight(10);

        Region spacerLarge2 = new Region();
        spacerLarge2.setMinHeight(10);

        Region spacerBottom = new Region();
        spacerBottom.setMinHeight(10);


// --------------------
// Buttons (DATA / SYNC)
// --------------------
        Button btnFirstSetup = new Button("First-time DB Setup (Base Fill)");
        btnFirstSetup.setPrefWidth(320);
        btnFirstSetup.setStyle(buttonStyle());

        Button btnSyncTpItems = new Button("Sync tradeable Items");
        btnSyncTpItems.setPrefWidth(320);
        btnSyncTpItems.setStyle(buttonStyle());

        Button btnSyncAccount = new Button("Sync Account (Bank, Mats, Characters, Recipes)");
        btnSyncAccount.setPrefWidth(320);
        btnSyncAccount.setStyle(buttonStyle());

        Button btnBank = new Button("See Bank");
        btnBank.setPrefWidth(180);
        btnBank.setStyle(buttonStyle());
        btnBank.setOnAction(e -> BankView.show(stage, () -> stage.setScene(homeScene)));

        Button btnMats = new Button("See Materials");
        btnMats.setPrefWidth(180);
        btnMats.setStyle(buttonStyle());
        btnMats.setOnAction(e -> MaterialsView.show(stage, () -> stage.setScene(homeScene)));


// Put the sync buttons in stack and row (centered)
        VBox syncButtons = new VBox(10,
                                    btnFirstSetup,
                                    btnSyncTpItems,
                                    btnSyncAccount
        );
        syncButtons.setAlignment(Pos.CENTER);

        HBox bottomRow = new HBox(10, btnBank, btnMats);
        bottomRow.setAlignment(Pos.CENTER);


// --------------------
// Buttons (TOOLS / FEATURES)
// --------------------
        Button btnEcto = new Button("Salvage Ecto for Dust & Luck");
        btnEcto.setPrefWidth(320);
        btnEcto.setStyle(buttonStyle());
        btnEcto.setOnAction(e -> EctoView.show(stage, () -> stage.setScene(homeScene)));

        Button btnCrafting = new Button("Crafting Profit Calculator");
        btnCrafting.setPrefWidth(320);
        btnCrafting.setStyle(buttonStyle());
        btnCrafting.setOnAction(e -> CraftingProfitView.show(stage, () -> stage.setScene(homeScene)));

        Button btnCraftDiscover = new Button("Crafting Discover Helper");
        btnCraftDiscover.setPrefWidth(320);
        btnCraftDiscover.setStyle(buttonStyle());
        btnCraftDiscover.setOnAction(e -> CraftingDiscoveryView.show(stage, () -> stage.setScene(homeScene)));

        VBox toolButtons = new VBox(10, btnEcto, btnCrafting, btnCraftDiscover);
        toolButtons.setAlignment(Pos.CENTER);



// --------------------
// Button actions (threads) - plug into your existing logic
// --------------------

// 1) First-time base fill
        btnFirstSetup.setOnAction(e -> {

            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("Initial DB Fill");
            a.setHeaderText("Fill empty DB in correct order?");
            a.setContentText("This is meant for the first run after a fresh DB reset.\n\nProceed?");
            Optional<ButtonType> result = a.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;

            btnFirstSetup.setDisable(true);
            status.setText("Initial fill running...");


            Thread t = new Thread(() -> {
                try {
                    InitialSetupService.firstFill();

                    Platform.runLater(() -> {
                        status.setText("✅ Initial fill finished.");
                        btnFirstSetup.setDisable(false);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Initial fill failed: " + ex.getMessage());
                        btnFirstSetup.setDisable(false);
                    });
                }
            });

            t.setDaemon(true);
            t.start();
        });

        // 3) Refresh list of items that can be traded on GW2 TP
        btnSyncTpItems.setOnAction(e -> {
            btnSyncTpItems.setDisable(true);
            status.setText("Refreshing TradePost Items...");

            Thread t = new Thread(() -> {
                try {
                    TpSync.syncTpTradeableItems();

                    Platform.runLater(() -> {
                        status.setText("✅ TradePost Items refreshed.");
                        btnSyncTpItems.setDisable(false);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Refresh failed: " + ex.getMessage());
                        btnSyncTpItems.setDisable(false);
                    });
                }
            });
            t.setDaemon(true);
            t.start();
        });

// 3) Refresh mats + bank + recepies + Char crafting
        btnSyncAccount.setOnAction(e -> {
            btnSyncAccount.setDisable(true);
            status.setText("Refreshing Account data...");

            Thread t = new Thread(() -> {
                try {
                    AccountSync.syncAccountBank();
                    AccountSync.syncAccountMaterials();
                    AccountSync.syncAccountRecipes();
                    CharacterSync.syncCharactersCraftingAndRecipes();

                    Platform.runLater(() -> {
                        status.setText("✅ Account data refreshed.");
                        btnSyncAccount.setDisable(false);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Refresh failed: " + ex.getMessage());
                        btnSyncAccount.setDisable(false);
                    });
                }
            });
            t.setDaemon(true);
            t.start();
        });

// --------------------
// Assemble root
// --------------------
        VBox root = new VBox(
                14,
                titleBox,
                subtitle,
                apiRow,
                spacerLarge1,

                syncButtons,
                spacerLarge2,

                toolButtons,
                spacerSmall,

                spacerBottom,
                bottomRow,

                status
        );


        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #111318;");

        return new Scene(root, 1280, 720);

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
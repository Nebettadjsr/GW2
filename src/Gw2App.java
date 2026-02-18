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
import repo.AppConfig;
import javafx.scene.control.TextArea;
import java.io.OutputStream;
import java.io.PrintStream;

import java.util.Objects;
import java.util.Optional;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;


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

        Label status = new Label("");
        status.setStyle("-fx-text-fill: #c9d1d9; -fx-opacity: 0.9;");

        TextArea console = new TextArea();
        console.setEditable(false);
        console.setWrapText(true);
        console.setPrefWidth(900);
        console.setPrefHeight(250);
        console.setStyle("""
    -fx-control-inner-background: #0b0d12;
    -fx-text-fill: #c9d1d9;
    -fx-font-family: Consolas;
    -fx-font-size: 12px;
""");

// hidden initially
        console.setVisible(false);
        console.setManaged(false);

        redirectSystemOut(console);
        redirectSystemErr(console);



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

        Button btnRefreshAccountRecipes = new Button("Refresh Account Recipes");
        btnRefreshAccountRecipes.setPrefWidth(320);
        btnRefreshAccountRecipes.setStyle(buttonStyle());

        Button btnRefreshMatsBank = new Button("Refresh Account Mats + Bank");
        btnRefreshMatsBank.setPrefWidth(320);
        btnRefreshMatsBank.setStyle(buttonStyle());

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
                btnRefreshAccountRecipes,
                btnRefreshMatsBank
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

        VBox toolButtons = new VBox(10, btnEcto, btnCrafting);
        toolButtons.setAlignment(Pos.CENTER);

// --------------------
// EXISTING: status label (keep yours)
// --------------------
// status already exists above in your code

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

            console.clear();
            console.setVisible(true);
            console.setManaged(true);


            Thread t = new Thread(() -> {
                try {
                    // You said you already added DB bootstrap earlier:
                    // DbBootstrap.ensureDatabaseExists(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS);
                    InitialSetupService.firstFill();

                    Platform.runLater(() -> {
                        status.setText("✅ Initial fill finished.");
                        btnFirstSetup.setDisable(false);

                        console.setVisible(false);
                        console.setManaged(false);
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


// 3) Refresh account recipes only
        btnRefreshAccountRecipes.setOnAction(e -> {
            btnRefreshAccountRecipes.setDisable(true);
            status.setText("Refreshing account recipes...");

            Thread t = new Thread(() -> {
                try {
                    Gw2DbSync.syncAccountRecipes();
                    Platform.runLater(() -> {
                        status.setText("✅ Account recipes refreshed.");
                        btnRefreshAccountRecipes.setDisable(false);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Refresh failed: " + ex.getMessage());
                        btnRefreshAccountRecipes.setDisable(false);
                    });
                }
            });
            t.setDaemon(true);
            t.start();
        });

// 4) Refresh mats + bank
        btnRefreshMatsBank.setOnAction(e -> {
            btnRefreshMatsBank.setDisable(true);
            status.setText("Refreshing mats + bank...");

            Thread t = new Thread(() -> {
                try {
                    Gw2DbSync.syncAccountMaterials();
                    Gw2DbSync.syncAccountBank();

                    Platform.runLater(() -> {
                        status.setText("✅ Mats + Bank refreshed.");
                        btnRefreshMatsBank.setDisable(false);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("❌ Refresh failed: " + ex.getMessage());
                        btnRefreshMatsBank.setDisable(false);
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

                status,
                console // stays hidden until needed
        );



        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #111318;");

        return new Scene(root, 1280, 720);

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

    private void redirectSystemOut(TextArea console) {
        PrintStream ps = new PrintStream(new OutputStream() {
            @Override public void write(int b) { append(console, String.valueOf((char) b)); }
            @Override public void write(byte[] b, int off, int len) { append(console, new String(b, off, len)); }
        }, true);
        System.setOut(ps);
    }

    private void redirectSystemErr(TextArea console) {
        PrintStream ps = new PrintStream(new OutputStream() {
            @Override public void write(int b) { append(console, String.valueOf((char) b)); }
            @Override public void write(byte[] b, int off, int len) { append(console, new String(b, off, len)); }
        }, true);
        System.setErr(ps);
    }

    private void append(TextArea console, String text) {
        Platform.runLater(() -> {
            console.appendText(text);
            console.positionCaret(console.getText().length());
        });
    }


    public static void main(String[] args) {
        launch(args);
    }
}
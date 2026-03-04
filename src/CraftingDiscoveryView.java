import craft.CraftResult;
import craft.CraftingSettings;
import craft.Node;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import repo.DiscChoice;
import sync.AccountSync;
import sync.CharacterSync;
import sync.TpSync;
import util.CoinUtils;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CraftingDiscoveryView {
    private static ScheduledExecutorService scheduler;

    // ---------- Row model ----------
    public static class DiscoverRow {
        private final IntegerProperty recipeId = new SimpleIntegerProperty();
        private final IntegerProperty outputItemId = new SimpleIntegerProperty();
        private final StringProperty outputName = new SimpleStringProperty();

        private final IntegerProperty buyCostCopper = new SimpleIntegerProperty();
        private final IntegerProperty revenueCopper = new SimpleIntegerProperty();
        private final IntegerProperty profitCopper = new SimpleIntegerProperty();
        private final IntegerProperty recipeLevel = new SimpleIntegerProperty();
        private final StringProperty missingSummary = new SimpleStringProperty();

        public DiscoverRow(int recipeId, int outputItemId, String outputName, int recipeLevel,
                           int buyCostCopper, int revenueCopper, int profitCopper,
                           String missingSummary) {
            this.recipeId.set(recipeId);
            this.outputItemId.set(outputItemId);
            this.outputName.set(outputName);
            this.recipeLevel.set(recipeLevel);
            this.buyCostCopper.set(buyCostCopper);
            this.revenueCopper.set(revenueCopper);
            this.profitCopper.set(profitCopper);
            this.missingSummary.set(missingSummary);
        }

        public int getRecipeLevel() { return recipeLevel.get(); }
        public IntegerProperty recipeLevelProperty() { return recipeLevel; }

        public int getRecipeId() { return recipeId.get(); }
        public IntegerProperty recipeIdProperty() { return recipeId; }

        public int getOutputItemId() { return outputItemId.get(); }
        public IntegerProperty outputItemIdProperty() { return outputItemId; }

        public String getOutputName() { return outputName.get(); }
        public StringProperty outputNameProperty() { return outputName; }

        public int getBuyCostCopper() { return buyCostCopper.get(); }
        public IntegerProperty buyCostCopperProperty() { return buyCostCopper; }

        public int getRevenueCopper() { return revenueCopper.get(); }
        public IntegerProperty revenueCopperProperty() { return revenueCopper; }

        public int getProfitCopper() { return profitCopper.get(); }
        public IntegerProperty profitCopperProperty() { return profitCopper; }

        public String getMissingSummary() { return missingSummary.get(); }
        public StringProperty missingSummaryProperty() { return missingSummary; }

    }

    public static void show(Stage stage, Runnable onBack) {

        // ---------- Top bar ----------
        Button btnBack = new Button("← Back");
        btnBack.setOnAction(e -> {
            if (scheduler != null) scheduler.shutdownNow();
            onBack.run();
        });

        HBox topBar = new HBox(btnBack);
        topBar.setPadding(new Insets(10));

        // ---------- Title + status ----------
        Label title = new Label("Crafting Discovery Helper");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label statusLabel = new Label("Pick a discipline+character to list missing DISCOVERABLE recipes.");
        statusLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        CraftingDiscoveryController controller = new CraftingDiscoveryController();

        // ---------- Filter bar ----------
        ComboBox<DiscChoice> disciplineBox = new ComboBox<>();
        disciplineBox.setPrefWidth(320);

        repo.CharacterRepository charRepo = new repo.CharacterRepository();

        Runnable reloadDisciplineChoices = () -> {
            Thread t = new Thread(() -> {
                try {
                    var crafts = charRepo.loadAllCharacterCrafting(); // rows: discipline, rating, charName
                    Platform.runLater(() -> {
                        disciplineBox.getItems().clear();

                        // IMPORTANT for discovery: only CHAR_DISCIPLINE entries
                        for (var row : crafts) {
                            disciplineBox.getItems().add(
                                    DiscChoice.charDiscipline(row.discipline, row.rating, row.charName)
                            );
                        }

                        if (!disciplineBox.getItems().isEmpty()) {
                            disciplineBox.getSelectionModel().selectFirst();
                        }
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> statusLabel.setText("❌ Failed loading characters: " + ex.getMessage()));
                }
            });
            t.setDaemon(true);
            t.start();
        };

        CheckBox bankOnlyCheck = new CheckBox("use mats from Bank ");
        bankOnlyCheck.setSelected(true);
        bankOnlyCheck.setStyle("-fx-text-fill: white;");

        CheckBox allowBuyCheck = new CheckBox("buy missing mats");
        allowBuyCheck.setSelected(true); // discovery usually needs buying
        allowBuyCheck.setStyle("-fx-text-fill: white;");

        TextField maxBudgetField = new TextField("20g");
        maxBudgetField.setPrefWidth(80);
        maxBudgetField.setDisable(false);

        allowBuyCheck.selectedProperty().addListener((obs, oldV, newV) -> {
            maxBudgetField.setDisable(!newV);
        });

        // Sell mode toggle (only affects displayed sell prices, not discovery)
        ToggleGroup sellMode = new ToggleGroup();
        RadioButton rbInstantSell = new RadioButton("Instant sell");
        RadioButton rbListingSell = new RadioButton("Listing sell");
        rbInstantSell.setToggleGroup(sellMode);
        rbListingSell.setToggleGroup(sellMode);
        rbInstantSell.setSelected(true);
        styleRadio(rbInstantSell);
        styleRadio(rbListingSell);

        // Buy mode toggle
        ToggleGroup buyMode = new ToggleGroup();
        RadioButton rbInstantBuy = new RadioButton("Instant buy");
        RadioButton rbListingBuy = new RadioButton("Listing buy");
        rbInstantBuy.setToggleGroup(buyMode);
        rbListingBuy.setToggleGroup(buyMode);
        rbInstantBuy.setSelected(true);
        styleRadio(rbInstantBuy);
        styleRadio(rbListingBuy);

        ComboBox<String> sortBox = new ComboBox<>();
        sortBox.getItems().addAll(
                "Recipe level (high first)",
                "Buy cost (low first)",
                "Item sell price (high first)",
                "Profit per craft (high first)"
        );
        sortBox.getSelectionModel().select(0);
        sortBox.setPrefWidth(190);

        TextField searchField = new TextField();
        searchField.setPromptText("Search item...");
        searchField.setPrefWidth(220);

        // --- Row 1 ---
        HBox filterRow1 = new HBox(12,
                new LabelStyled("Discipline+Char:"),
                disciplineBox,
                bankOnlyCheck,
                allowBuyCheck,
                new LabelStyled("Max buy:"), maxBudgetField,
                new Separator(Orientation.VERTICAL)
        );
        filterRow1.setAlignment(Pos.CENTER);

        // --- Row 2 ---
        Button btnRefreshTp = new Button("Refresh Trade Post Prices");
        Button btnRefresh = new Button("Refresh");

        HBox filterRow2 = new HBox(12,
                new LabelStyled("BUY:"), rbInstantBuy, rbListingBuy,
                new Separator(Orientation.VERTICAL),
                new LabelStyled("SELL:"), rbInstantSell, rbListingSell,
                new Separator(Orientation.VERTICAL)

        );
        filterRow2.setAlignment(Pos.CENTER);

        // --- Row 3 ---
        Label autoRefreshLabel = new Label("Auto-refresh in: 120s");
        autoRefreshLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        Label lastRefreshLabel = new Label("Last refresh: —");
        lastRefreshLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.65;");

        HBox filterRow3 = new HBox(12,
                new LabelStyled("Sort:"), sortBox,
                searchField,
                btnRefreshTp,
                btnRefresh
                );
        filterRow3.setAlignment(Pos.CENTER);

        HBox filterRow4 = new HBox(12, autoRefreshLabel, lastRefreshLabel);
        filterRow4.setAlignment(Pos.CENTER);

        VBox filterBar = new VBox(10, filterRow1, filterRow2, filterRow3, filterRow4);
        filterBar.setPadding(new Insets(10));
        filterBar.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                        "-fx-border-color: rgba(255,255,255,0.06);" +
                        "-fx-border-radius: 10;" +
                        "-fx-background-radius: 10;"
        );

        // ---------- Main table ----------
        TableView<DiscoverRow> table = new TableView<>();
        table.setMaxWidth(Double.MAX_VALUE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No data yet."));
        table.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-control-inner-background: #12151c;" +
                        "-fx-table-cell-border-color: rgba(255,255,255,0.08);" +
                        "-fx-table-header-border-color: rgba(255,255,255,0.08);"
        );

        ObservableList<DiscoverRow> rows = FXCollections.observableArrayList();
        table.setItems(rows);

        // --- reload logic ---
        Runnable reloadTable = () -> {
            DiscChoice choice = disciplineBox.getValue();
            if (choice == null || choice.kind != DiscChoice.Kind.CHAR_DISCIPLINE) {
                Platform.runLater(() -> statusLabel.setText("Pick a 'Discipline lvl — Character' entry."));
                return;
            }

            statusLabel.setText("Loading missing discoverable recipes...");
            int maxBuyCopper = CoinUtils.parseToCopper(maxBudgetField.getText());

            Thread t = new Thread(() -> {
                try {
                    boolean includeBank = bankOnlyCheck.isSelected();
                    boolean allowBuy = allowBuyCheck.isSelected();
                    boolean listingSell = rbListingSell.isSelected();
                    boolean listingBuy  = rbListingBuy.isSelected();

                    // dailyBuyMode not relevant for discovery; keep false
                    boolean dailyBuyMode = false;

                    CraftingSettings settings = new CraftingSettings(
                            includeBank, allowBuy, maxBuyCopper, listingSell, listingBuy, dailyBuyMode
                    );

                    String search = searchField.getText();

                    var data = controller.reload(choice, settings, search);

                    Platform.runLater(() -> {
                        rows.clear();
                        for (var r : data) {
                            rows.add(new DiscoverRow(
                                    r.recipeId,
                                    r.outputItemId,
                                    r.outputName,
                                    r.recipeLevel,
                                    r.buyCostCopper,
                                    r.revenueCopper,
                                    r.profitCopper,
                                    r.missingSummary
                            ));
                        }

                        applySort(sortBox.getValue(), rows);
                        table.refresh();

                        statusLabel.setText("✅ Loaded " + rows.size() + " missing discoverable recipes.");
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> statusLabel.setText("❌ Load failed: " + ex.getMessage()));
                }
            });

            t.setDaemon(true);
            t.start();
        };

        // ---------- Auto-refresh (materials + bank) ----------
        final int REFRESH_SECONDS = 120;

        if (scheduler != null) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        final int[] secondsLeft = { REFRESH_SECONDS };

        scheduler.scheduleAtFixedRate(() -> {
            secondsLeft[0]--;

            if (secondsLeft[0] <= 0) {
                try {
                    AccountSync.syncAccountBank();
                    AccountSync.syncAccountMaterials();
                    AccountSync.syncAccountRecipes();     // vendor/learned-from-item/autolearned etc (account-wide)
                    CharacterSync.syncCharactersCraftingAndRecipes();    // discovery per character

                    secondsLeft[0] = REFRESH_SECONDS;

                    Platform.runLater(() -> {
                        lastRefreshLabel.setText("Last refresh: just now");
                        statusLabel.setText("🔄 Auto-refreshed Bank + Materials");
                        reloadTable.run();
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    secondsLeft[0] = REFRESH_SECONDS;
                    Platform.runLater(() -> {
                        lastRefreshLabel.setText("Last refresh: FAILED");
                        statusLabel.setText("⚠️ Auto-refresh failed: " + ex.getMessage());
                    });
                }
            }

            int show = secondsLeft[0];
            Platform.runLater(() -> autoRefreshLabel.setText("Auto-refresh in: " + show + "s"));

        }, 1, 1, TimeUnit.SECONDS);

        // --- listeners ---
        disciplineBox.valueProperty().addListener((obs, o, n) -> reloadTable.run());
        bankOnlyCheck.selectedProperty().addListener((obs, o, n) -> reloadTable.run());
        allowBuyCheck.selectedProperty().addListener((obs, o, n) -> reloadTable.run());
        rbInstantBuy.selectedProperty().addListener((obs, o, n) -> { if (n) reloadTable.run(); });
        rbListingBuy.selectedProperty().addListener((obs, o, n) -> { if (n) reloadTable.run(); });
        rbInstantSell.selectedProperty().addListener((obs, o, n) -> { if (n) reloadTable.run(); });
        rbListingSell.selectedProperty().addListener((obs, o, n) -> { if (n) reloadTable.run(); });

        maxBudgetField.textProperty().addListener((obs, o, n) -> {
            if (allowBuyCheck.isSelected()) reloadTable.run();
        });

        searchField.setOnAction(e -> reloadTable.run());

        btnRefreshTp.setOnAction(e -> {
            statusLabel.setText("Refreshing TP prices...");
            Thread tt = new Thread(() -> {
                try {
                    TpSync.syncTpPricesRelevant();
                    Platform.runLater(() -> {
                        statusLabel.setText("✅ TP refreshed.");
                        reloadTable.run();
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> statusLabel.setText("❌ TP refresh failed: " + ex.getMessage()));
                }
            });
            tt.setDaemon(true);
            tt.start();
        });

        btnRefresh.setOnAction(e -> reloadTable.run());

        // ---------- Columns ----------
        TableColumn<DiscoverRow, String> colName = new TableColumn<>("Item");
        colName.setCellValueFactory(data -> data.getValue().outputNameProperty());

        TableColumn<DiscoverRow, Number> colLevel = new TableColumn<>("Level");
        colLevel.setCellValueFactory(d -> d.getValue().recipeLevelProperty());
        colLevel.setSortType(TableColumn.SortType.DESCENDING);

        TableColumn<DiscoverRow, Number> colBuyCost = new TableColumn<>("Buy cost");
        colBuyCost.setCellValueFactory(data -> data.getValue().buyCostCopperProperty());
        colBuyCost.setCellFactory(tc -> coinCell(false));

        TableColumn<DiscoverRow, Number> colSell = new TableColumn<>("Item sell price");
        colSell.setCellValueFactory(data -> data.getValue().revenueCopperProperty());
        colSell.setCellFactory(tc -> coinCell(false));

        TableColumn<DiscoverRow, Number> colProfit = new TableColumn<>("Profit per craft");
        colProfit.setCellValueFactory(data -> data.getValue().profitCopperProperty());
        colProfit.setCellFactory(tc -> profitCell());
        // after all columns are added:
        table.getSortOrder().setAll(colLevel);

        // widths
        colLevel.setMaxWidth(90);
        colLevel.setMinWidth(70);
        colBuyCost.setMaxWidth(130);
        colSell.setMaxWidth(150);
        colProfit.setMaxWidth(150);

        colName.setMaxWidth(1f * Integer.MAX_VALUE);

        colName.setMinWidth(240);
        colBuyCost.setMinWidth(110);
        colSell.setMinWidth(130);
        colProfit.setMinWidth(130);

        table.getColumns().addAll(colName, colLevel, colBuyCost, colSell, colProfit);

        // ---------- Details panel (right) ----------
        Label detailsTitle = new Label("Details");
        detailsTitle.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");

        Label selectedItemLabel = new Label("Select an item to see recipe tree + shopping list.");
        selectedItemLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        TreeView<String> recipeTree = new TreeView<>();
        recipeTree.setPrefHeight(280);
        recipeTree.setStyle("-fx-control-inner-background: #12151c; -fx-text-fill: white;");

        Label shoppingTitle = new Label("Shopping list (aggregated)");
        shoppingTitle.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-opacity: 0.95;");

        ListView<String> shoppingList = new ListView<>();
        shoppingList.setPrefHeight(230);
        shoppingList.setStyle("-fx-control-inner-background: #12151c; -fx-text-fill: white;");

        VBox detailsCard = card(detailsTitle, selectedItemLabel,
                new LabelStyled("Recipe tree:"), recipeTree,
                shoppingTitle, shoppingList
        );

        // bigger than profit view
        detailsCard.setMinWidth(420);
        detailsCard.setMaxWidth(420);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, rowSel) -> {
            if (rowSel == null) return;

            selectedItemLabel.setText(rowSel.getOutputName());

            CraftResult res = controller.getResultByRecipeId(rowSel.getRecipeId());
            if (res == null) {
                recipeTree.setRoot(new TreeItem<>("(no details)"));
                shoppingList.getItems().setAll();
                return;
            }

            // tree
            TreeItem<String> root = toTreeItem(res.tree, controller);
            root.setExpanded(true);
            recipeTree.setRoot(root);

            // shopping list
            boolean listingSellMode = rbListingSell.isSelected();

            shoppingList.getItems().setAll(
                    res.missingToBuyOne.entrySet().stream()
                            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                            .map(e -> {
                                int itemId = e.getKey();
                                int qty = e.getValue();

                                String name = controller.itemName(itemId);

                                int stacks = qty / 250;
                                int rest = qty % 250;

                                String stackText = "";
                                if (stacks > 0) {
                                    stackText = " (" + stacks + "x250";
                                    if (rest > 0) stackText += " + " + rest;
                                    stackText += ")";
                                }

                                int unit = controller.itemSellUnit(itemId, listingSellMode);
                                int total = unit * qty;

                                return qty + " x " +name +  stackText +
                                        "\n 1 = " + CoinUtils.format(unit) +
                                        " | total = " + CoinUtils.format(total);
                            })
                            .toList()
            );
        });

        sortBox.valueProperty().addListener((obs, o, n) -> {
            applySort(n, rows);
            table.refresh();
        });

        // ---------- Main area ----------
        HBox mainArea = new HBox(14, table, detailsCard);
        HBox.setHgrow(table, Priority.ALWAYS);
        mainArea.setAlignment(Pos.TOP_CENTER);
        mainArea.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(14, title, statusLabel, filterBar, mainArea);
        content.setAlignment(Pos.TOP_CENTER);
        content.setPadding(new Insets(20, 20, 24, 20));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0f1115; -fx-background-color: #0f1115;");

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(scroll);
        root.setStyle("""
            -fx-background-color: #0f1115;
            -fx-font-size: 15px;
        """);

        stage.setScene(new Scene(root, 1280, 720));

        reloadDisciplineChoices.run();
        // after choice list loads, selection listener triggers reloadTable()

        Platform.runLater(() -> {
            var header = table.lookup("TableHeaderRow");
            if (header != null) header.setStyle("-fx-background-color: #0f1115;");

            table.lookupAll(".column-header").forEach(node ->
                    node.setStyle(
                            "-fx-background-color: #0f1115;" +
                                    "-fx-border-color: rgba(255,255,255,0.08);" +
                                    "-fx-text-fill: white;"
                    )
            );

            table.lookupAll(".label").forEach(node ->
                    node.setStyle("-fx-text-fill: white;")
            );

            table.getSortOrder().setAll(colLevel);
            table.sort();
        });
    }

    // ---------- Sorting ----------
    private static void applySort(String mode, ObservableList<DiscoverRow> rows) {
        if (mode == null || rows == null) return;

        switch (mode) {
            case "Recipe level (high first)" ->
                    FXCollections.sort(rows, Comparator.comparingInt(DiscoverRow::getRecipeLevel).reversed());

            case "Buy cost (low first)" ->
                    FXCollections.sort(rows, Comparator.comparingInt(DiscoverRow::getBuyCostCopper));

            case "Item sell price (high first)" ->
                    FXCollections.sort(rows, Comparator.comparingInt(DiscoverRow::getRevenueCopper).reversed());

            case "Profit per craft (high first)" ->
                    FXCollections.sort(rows, Comparator.comparingInt(DiscoverRow::getProfitCopper).reversed());

            default -> { /* do nothing */ }
        }
    }

    // ---------- Styling helpers ----------
    private static VBox card(javafx.scene.Node... children) {
        VBox v = new VBox(10, children);
        v.setPadding(new Insets(12));
        v.setStyle(
                "-fx-background-color: rgba(255,255,255,0.02);" +
                        "-fx-border-color: rgba(255,255,255,0.05);" +
                        "-fx-border-radius: 10;" +
                        "-fx-background-radius: 10;"
        );
        return v;
    }

    private static class LabelStyled extends Label {
        LabelStyled(String text) {
            super(text);
            setStyle("-fx-text-fill: white; -fx-opacity: 0.9;");
        }
    }

    private static void styleRadio(RadioButton rb) {
        rb.setStyle("-fx-text-fill: white; -fx-opacity: 0.95;");
    }

    private static TableCell<DiscoverRow, Number> coinCell(boolean signed) {
        return new TableCell<>() {
            @Override protected void updateItem(Number copper, boolean empty) {
                super.updateItem(copper, empty);
                if (empty || copper == null) {
                    setText(null);
                    return;
                }
                int v = copper.intValue();
                setText((signed ? CoinUtils.formatSigned(v) : CoinUtils.format(v)));
                setStyle("-fx-text-fill: white; -fx-font-family: 'Consolas';");
            }
        };
    }

    private static TableCell<DiscoverRow, Number> profitCell() {
        return new TableCell<>() {
            @Override protected void updateItem(Number copper, boolean empty) {
                super.updateItem(copper, empty);
                if (empty || copper == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                int v = copper.intValue();
                setText(CoinUtils.formatSigned(v));
                if (v > 0) {
                    setStyle("-fx-text-fill: #7CFC98; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
                } else if (v < 0) {
                    setStyle("-fx-text-fill: #FF7C7C; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: white; -fx-font-family: 'Consolas';");
                }
            }
        };
    }

    private static TreeItem<String> toTreeItem(Node n, CraftingDiscoveryController controller) {
        if (n == null) return new TreeItem<>("(no data)");

        String label = controller.itemName(n.itemId) + " x" + n.qty + "  [" + n.action + "]";
        TreeItem<String> ti = new TreeItem<>(label);

        if (n.children != null) {
            for (Node ch : n.children) {
                ti.getChildren().add(toTreeItem(ch, controller));
            }
        }
        return ti;
    }

}
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import java.util.Comparator;
import java.util.Map;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;


public class CraftingProfitView {
    private static ScheduledExecutorService scheduler;


    // ---------- Dummy row model (we replace with DB-backed model later) ----------
    public static class CraftRow {
        private final IntegerProperty recipeId = new SimpleIntegerProperty();
        private final IntegerProperty outputItemId = new SimpleIntegerProperty();
        private final StringProperty outputName = new SimpleStringProperty();
        private final StringProperty discipline = new SimpleStringProperty();

        private final IntegerProperty craftableCount = new SimpleIntegerProperty();
        private final StringProperty missingSummary = new SimpleStringProperty();

        private final IntegerProperty buyCostCopper = new SimpleIntegerProperty();
        private final IntegerProperty revenueCopper = new SimpleIntegerProperty();
        private final IntegerProperty profitCopper = new SimpleIntegerProperty();

        private final IntegerProperty matsSellValueCopper = new SimpleIntegerProperty();


        public CraftRow(int recipeId, int outputItemId, String outputName, String discipline,
                        int craftableCount, String missingSummary,
                        int buyCostCopper, int revenueCopper, int profitCopper, int matsSellValueCopper) {
            this.recipeId.set(recipeId);
            this.outputItemId.set(outputItemId);
            this.outputName.set(outputName);
            this.discipline.set(discipline);
            this.craftableCount.set(craftableCount);
            this.missingSummary.set(missingSummary);
            this.buyCostCopper.set(buyCostCopper);
            this.revenueCopper.set(revenueCopper);
            this.profitCopper.set(profitCopper);
            this.matsSellValueCopper.set(matsSellValueCopper);
        }

        public int getRecipeId() { return recipeId.get(); }
        public IntegerProperty recipeIdProperty() { return recipeId; }


        public int getOutputItemId() { return outputItemId.get(); }
        public IntegerProperty outputItemIdProperty() { return outputItemId; }

        public String getOutputName() { return outputName.get(); }
        public StringProperty outputNameProperty() { return outputName; }

        public String getDiscipline() { return discipline.get(); }
        public StringProperty disciplineProperty() { return discipline; }

        public int getCraftableCount() { return craftableCount.get(); }
        public IntegerProperty craftableCountProperty() { return craftableCount; }

        public String getMissingSummary() { return missingSummary.get(); }
        public StringProperty missingSummaryProperty() { return missingSummary; }

        public int getBuyCostCopper() { return buyCostCopper.get(); }
        public IntegerProperty buyCostCopperProperty() { return buyCostCopper; }

        public int getRevenueCopper() { return revenueCopper.get(); }
        public IntegerProperty revenueCopperProperty() { return revenueCopper; }

        public int getProfitCopper() { return profitCopper.get(); }
        public IntegerProperty profitCopperProperty() { return profitCopper; }

        private final IntegerProperty totalProfitCopper = new SimpleIntegerProperty();
        public int getTotalProfitCopper() { return totalProfitCopper.get(); }
        public IntegerProperty totalProfitCopperProperty() { return totalProfitCopper; }

//        private final IntegerProperty matsSellValueCopper = new SimpleIntegerProperty();
        public int getMatsSellValueCopper() { return matsSellValueCopper.get(); }
        public IntegerProperty matsSellValueCopperProperty() { return matsSellValueCopper; }

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
        Label title = new Label("Crafting Profit Analyzer");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label statusLabel = new Label("UI ready. (Next: load recipes + inventory + TP prices from DB)");
        statusLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");
        CraftingProfitController controller = new CraftingProfitController();


        // ---------- Filter bar ----------
        ComboBox<String> disciplineBox = new ComboBox<>();
        disciplineBox.getItems().addAll(
                "All",
                "Chef",
                "Huntsman",
                "Weaponsmith",
                "Armorsmith",
                "Artificer",
                "Tailor",
                "Leatherworker",
                "Jeweler",
                "Scribe"
                                       );
        disciplineBox.getSelectionModel().select("All");
        disciplineBox.setPrefWidth(170);

        CheckBox bankOnlyCheck = new CheckBox("use mats from Bank ");
        bankOnlyCheck.setSelected(true);
        bankOnlyCheck.setStyle("-fx-text-fill: white;");

        CheckBox allowBuyCheck = new CheckBox("buy missing mats");
        allowBuyCheck.setSelected(false);
        allowBuyCheck.setStyle("-fx-text-fill: white;");

        TextField maxBudgetField = new TextField("20g");
        maxBudgetField.setPrefWidth(80);
        maxBudgetField.setDisable(true);

        allowBuyCheck.selectedProperty().addListener((obs, oldV, newV) -> {
            maxBudgetField.setDisable(!newV);
        });

        // Sell mode toggle
        ToggleGroup sellMode = new ToggleGroup();
        RadioButton rbInstantSell = new RadioButton("Instant sell");
        RadioButton rbListingSell = new RadioButton("Listing sell");
        rbInstantSell.setToggleGroup(sellMode);
        rbListingSell.setToggleGroup(sellMode);
        rbInstantSell.setSelected(true);
        styleRadio(rbInstantSell);
        styleRadio(rbListingSell);


        // Buy mode toggle (for missing mats)
        ToggleGroup buyMode = new ToggleGroup();
        RadioButton rbInstantBuy = new RadioButton("Instant buy");
        RadioButton rbListingBuy = new RadioButton("Listing buy");
        rbInstantBuy.setToggleGroup(buyMode);
        rbListingBuy.setToggleGroup(buyMode);
        rbInstantBuy.setSelected(true);
        styleRadio(rbInstantBuy);
        styleRadio(rbListingBuy);

        // toggle for daily craftables
        ToggleGroup dailyBuyTogGroup = new ToggleGroup();
        RadioButton dailyCraft = new RadioButton("craft");
        RadioButton dailyBuy = new RadioButton("buy");
        dailyCraft.setToggleGroup(dailyBuyTogGroup);
        dailyBuy.setToggleGroup(dailyBuyTogGroup);
        dailyBuy.setSelected(true);
        styleRadio(dailyCraft);
        styleRadio(dailyBuy);


        ComboBox<String> sortBox = new ComboBox<>();
        sortBox.getItems().addAll(
                "Profit per item",
                "Total profit",
                "Max craftable count",
                "ROI % (later)"
                                 );
        sortBox.getSelectionModel().select(0);
        sortBox.setPrefWidth(170);

        TextField searchField = new TextField();
        searchField.setPromptText("Search item...");
        searchField.setPrefWidth(220);

        // --- Row 1 ---
        HBox filterRow1 = new HBox(12,
                                   new LabelStyled("Discipline:"),
                                    disciplineBox,
                                    bankOnlyCheck,
                                    allowBuyCheck,
                                    new LabelStyled("Max buy:"), maxBudgetField,
                                    new Separator(Orientation.VERTICAL)
                            );
        filterRow1.setAlignment(Pos.CENTER);

// --- Row 2 ---
//        Button btnRefreshBank = new Button("Refresh Bank Materials");
        Button btnRefreshTp = new Button("Refresh Trade Post Prices");
        Button btnRefresh = new Button("Refresh");

        HBox filterRow2 = new HBox(12,
                                   new LabelStyled("Daily craftabls:"), dailyCraft, dailyBuy,
                                    new Separator(Orientation.VERTICAL),
                                   new LabelStyled("BUY:"), rbInstantBuy, rbListingBuy,
                                   new Separator(Orientation.VERTICAL),

                                   new LabelStyled("SELL:"), rbInstantSell, rbListingSell
        );

        filterRow2.setAlignment(Pos.CENTER);

// --- Row 3 ---
        Label autoRefreshLabel = new Label("Auto-refresh in: 90s");
        autoRefreshLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        Label lastRefreshLabel = new Label("Last refresh: —");
        lastRefreshLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.65;");


        HBox filterRow3 = new HBox(12,
                new LabelStyled("Sort:"), sortBox,
                new Separator(Orientation.VERTICAL),
                searchField,
                autoRefreshLabel,
                lastRefreshLabel,
                btnRefreshTp
        );
        filterRow3.setAlignment(Pos.CENTER);

// --- Wrapper card ---
        VBox filterBar = new VBox(10, filterRow1, filterRow2, filterRow3);
        filterBar.setPadding(new Insets(10));
        filterBar.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                        "-fx-border-color: rgba(255,255,255,0.06);" +
                        "-fx-border-radius: 10;" +
                        "-fx-background-radius: 10;"
                          );


        // ---------- Main table ----------
        TableView<CraftRow> table = new TableView<>();
        table.setMaxWidth(Double.MAX_VALUE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No data yet. (Next: DB load)"));
        table.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-control-inner-background: #12151c;" +     // rows
                        "-fx-table-cell-border-color: rgba(255,255,255,0.08);" +
                        "-fx-table-header-border-color: rgba(255,255,255,0.08);"
                      );

        // --- data list (must exist BEFORE reloadTable) ---
        ObservableList<CraftRow> rows = FXCollections.observableArrayList();
        table.setItems(rows);

// --- reload logic (must exist BEFORE button handlers that call it) ---
        Runnable reloadTable = () -> {
            statusLabel.setText("Loading from DB...");
            int maxBuyCopper = parseCoinToCopper(maxBudgetField.getText());
            statusLabel.setText("MaxBuy UI=" + maxBudgetField.getText() + " => " + maxBuyCopper + " copper");

            Thread t = new Thread(() -> {
                try {
                    String disc = disciplineBox.getValue();
                    boolean includeBank = bankOnlyCheck.isSelected();
                    boolean allowBuy = allowBuyCheck.isSelected();
                    boolean listingSell = rbListingSell.isSelected();
                    boolean listingBuy  = rbListingBuy.isSelected();
                    boolean dailyBuyMode = dailyBuy.isSelected(); // true = buy daily items, false = craft daily items

                    CraftingSettings settings = new CraftingSettings(includeBank, allowBuy, maxBuyCopper, listingSell, listingBuy, dailyBuyMode);

                    String search = searchField.getText();

                    var data = controller.reload(disc, settings, search);


                    Platform.runLater(() -> {
                        rows.clear();
                        for (var r : data) {
                            rows.add(new CraftRow(
                                    r.recipeId,
                                    r.outputItemId,
                                    r.outputName,
                                    r.discipline,
                                    r.craftableCount,
                                    r.missingSummary,
                                    r.buyCostCopper,
                                    r.revenueCopper,
                                    r.profitCopper,
                                    r.matsSellValueCopper
                            ));
                        }
                        table.sort();

                        statusLabel.setText("✅ Loaded " + rows.size() + " recipes from DB.");
                    });


                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> statusLabel.setText("❌ DB load failed: " + ex.getMessage()));
                }
            });
            t.setDaemon(true);
            t.start();

        };
// stop previous scheduler if view is reopened
        final int REFRESH_SECONDS = 90;

        if (scheduler != null) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        final int[] secondsLeft = { REFRESH_SECONDS };

        scheduler.scheduleAtFixedRate(() -> {
            secondsLeft[0]--;

            if (secondsLeft[0] <= 0) {
                // do the refresh in this background thread
                try {
                    Gw2DbSync.syncAccountMaterials();
                    Gw2DbSync.syncAccountBank();

                    // reset countdown
                    secondsLeft[0] = REFRESH_SECONDS;

                    Platform.runLater(() -> {
                        lastRefreshLabel.setText("Last refresh: just now");
                        statusLabel.setText("🔄 Auto-refreshed Bank + Materials");
                        reloadTable.run();
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    secondsLeft[0] = REFRESH_SECONDS; // still reset, otherwise it spams

                    Platform.runLater(() -> {
                        lastRefreshLabel.setText("Last refresh: FAILED");
                        statusLabel.setText("⚠️ Auto-refresh failed: " + ex.getMessage());
                    });
                }
            }

            // update countdown label every second
            int show = secondsLeft[0];
            Platform.runLater(() -> autoRefreshLabel.setText("Auto-refresh in: " + show + "s"));

        }, 1, 1, TimeUnit.SECONDS);


        // --- auto reload when filters change ---
        disciplineBox.valueProperty().addListener((obs, o, n) -> reloadTable.run());

        bankOnlyCheck.selectedProperty().addListener((obs, o, n) -> reloadTable.run());

        allowBuyCheck.selectedProperty().addListener((obs, o, n) -> reloadTable.run());

        dailyCraft.selectedProperty().addListener((obs,o,n) -> { if (n) reloadTable.run(); });
        dailyBuy.selectedProperty().addListener((obs,o,n) -> { if (n) reloadTable.run(); });

        rbInstantBuy.selectedProperty().addListener((obs,o,n) -> { if(n) reloadTable.run(); });
        rbListingBuy.selectedProperty().addListener((obs,o,n) -> { if(n) reloadTable.run(); });

        rbInstantSell.selectedProperty().addListener((obs,o,n) -> { if(n) reloadTable.run(); });
        rbListingSell.selectedProperty().addListener((obs,o,n) -> { if(n) reloadTable.run(); });


// When allowBuy toggles, maxBudget field enable/disable changes -> still reload
        maxBudgetField.textProperty().addListener((obs, o, n) -> {
            if (allowBuyCheck.isSelected()) reloadTable.run();
        });

// Search: reload on ENTER (keeps it fast)
        searchField.setOnAction(e -> reloadTable.run());

// Sort: reload when changed
        sortBox.valueProperty().addListener((obs, o, n) -> reloadTable.run());

        btnRefreshTp.setOnAction(e -> {
            statusLabel.setText("Refreshing TP prices...");
            Thread t = new Thread(() -> {
                try {
                    Gw2DbSync.syncTpPricesRelevant();
                    Platform.runLater(() -> {
                        statusLabel.setText("✅ TP refreshed.");
                        reloadTable.run();
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> statusLabel.setText("❌ TP refresh failed: " + ex.getMessage()));
                }
            });
            t.setDaemon(true);
            t.start();
        });

        btnRefresh.setOnAction(e -> reloadTable.run());



        TableColumn<CraftRow, String> colName = new TableColumn<>("Item");
        colName.setCellValueFactory(data -> data.getValue().outputNameProperty());

        TableColumn<CraftRow, Number> colCraftable = new TableColumn<>("Craftable");
        colCraftable.setCellValueFactory(data -> data.getValue().craftableCountProperty());

//        TableColumn<CraftRow, String> colMissing = new TableColumn<>("Missing / To buy");
//        colMissing.setCellValueFactory(data -> data.getValue().missingSummaryProperty());

        TableColumn<CraftRow, Number> colBuyCost = new TableColumn<>("Buy cost");
        colBuyCost.setCellValueFactory(data -> data.getValue().buyCostCopperProperty());
        colBuyCost.setCellFactory(tc -> coinCell(false));

        TableColumn<CraftRow, Number> colRevenue = new TableColumn<>("Item sell price");
        colRevenue.setCellValueFactory(data -> data.getValue().revenueCopperProperty());
        colRevenue.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(Number copper, boolean empty) {
                super.updateItem(copper, empty);

                if (empty || copper == null) {
                    setText(null);
                    setStyle("");
                    return;
                }

                CraftRow row = getTableRow().getItem();
                if (row == null) {
                    setText(formatCoin(copper.intValue()));
                    return;
                }

                int revenue = copper.intValue();
                int matsValue = row.getMatsSellValueCopper();

                setText(formatCoin(revenue));

                // Compare: crafting vs selling mats
                if (revenue > matsValue) {
                    // crafting better
                    setStyle("-fx-text-fill: #7CFC98; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
                } else if (revenue < matsValue) {
                    // selling mats better
                    setStyle("-fx-text-fill: #FF7C7C; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: white; -fx-font-family: 'Consolas';");
                }
            }
        });


        TableColumn<CraftRow, Number> colProfit = new TableColumn<>("Profit per craft");
        colProfit.setCellValueFactory(data -> data.getValue().profitCopperProperty());
        colProfit.setCellFactory(tc -> profitCell());
        colProfit.setSortType(TableColumn.SortType.DESCENDING);

        TableColumn<CraftRow, Number> colTotalProfit = new TableColumn<>("Total profit");
        colTotalProfit.setCellValueFactory(data -> {

            int craftable = data.getValue().getCraftableCount();
            int profitPer = data.getValue().getProfitCopper();
            return new SimpleIntegerProperty(craftable * profitPer);
        });
        colTotalProfit.setCellFactory(tc -> profitCell());
        colTotalProfit.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().setAll(colTotalProfit);

        TableColumn<CraftRow, Number> colMatsSell = new TableColumn<>("Mats sell value");
        colMatsSell.setCellValueFactory(data -> data.getValue().matsSellValueCopperProperty());
        colMatsSell.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(Number copper, boolean empty) {
                super.updateItem(copper, empty);

                if (empty || copper == null) {
                    setText(null);
                    setStyle("");
                    return;
                }

                CraftRow row = getTableRow().getItem();
                if (row == null) {
                    setText(formatCoin(copper.intValue()));
                    return;
                }

                int matsValue = copper.intValue();
                int revenue = row.getRevenueCopper();

                setText(formatCoin(matsValue));

                // Opposite logic of Item Sell column
                if (matsValue > revenue) {
                    // selling mats better
                    setStyle("-fx-text-fill: #7CFC98; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
                } else if (matsValue < revenue) {
                    // crafting better
                    setStyle("-fx-text-fill: #FF7C7C; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: white; -fx-font-family: 'Consolas';");
                }
            }
        });



        // Make small columns stay small
        colCraftable.setMaxWidth(90);

        colBuyCost.setMaxWidth(120);
        colRevenue.setMaxWidth(140);
        colProfit.setMaxWidth(140);

        colTotalProfit.setMaxWidth(140);
        colTotalProfit.setMinWidth(140);

        colMatsSell.setMaxWidth(140);
        colMatsSell.setMinWidth(110);


// Give Item most of the width (weight)
        colName.setMaxWidth(1f * Integer.MAX_VALUE);

// Optional: small mins so they don’t get too tiny
        colCraftable.setMinWidth(70);
        colBuyCost.setMinWidth(90);
        colRevenue.setMinWidth(110);
        colProfit.setMinWidth(110);
        colName.setMinWidth(220);

        table.getColumns().addAll(colName, colCraftable, colBuyCost, colMatsSell, colRevenue, colProfit, colTotalProfit);



        // ---------- Details panel (right) ----------
        Label detailsTitle = new Label("Details");
        detailsTitle.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");

        Label selectedItemLabel = new Label("Select an item to see recipe tree + shopping list.");
        selectedItemLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        TreeView<String> recipeTree = new TreeView<>();
        recipeTree.setPrefHeight(240);
        recipeTree.setStyle("-fx-control-inner-background: #12151c; -fx-text-fill: white;");


        Label shoppingTitle = new Label("Shopping list (aggregated)");
        shoppingTitle.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-opacity: 0.95;");

        ListView<String> shoppingList = new ListView<>();
        shoppingList.setPrefHeight(180);
        shoppingList.setStyle("-fx-control-inner-background: #12151c; -fx-text-fill: white;");


        VBox detailsCard = card(detailsTitle, selectedItemLabel,
                                new LabelStyled("Recipe tree:"), recipeTree,
                                shoppingTitle, shoppingList
                               );
        detailsCard.setMinWidth(340);
        detailsCard.setMaxWidth(340);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, rowSel) -> {
            if (rowSel == null) return;

            selectedItemLabel.setText(rowSel.getOutputName() + "  \n" + rowSel.getDiscipline());

            CraftResult res = controller.getResultByRecipeId(rowSel.getRecipeId());
            if (res == null) {
                recipeTree.setRoot(new TreeItem<>("(no details)"));
                shoppingList.getItems().setAll();
                return;
            }

            // Recipe tree
            TreeItem<String> root = toTreeItem(res.tree, controller);
            root.setExpanded(true);
            recipeTree.setRoot(root);

            // Shopping list (aggregated)
            boolean listingSellMode = rbListingSell.isSelected();

            shoppingList.getItems().setAll(
                    res.missingToBuy.entrySet().stream()
                            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                            .map(e -> {
                                int itemId = e.getKey();
                                int qty = e.getValue();

                                String name = controller.itemName(itemId);

                                // --- stack calc ---
                                int stacks = qty / 250;
                                int rest = qty % 250;

                                String stackText = "";
                                if (stacks > 0) {
                                    stackText = " (" + stacks + "x250";
                                    if (rest > 0) stackText += " + " + rest;
                                    stackText += ")";
                                }

                                // --- price ---
                                int unit = controller.itemSellUnit(itemId, listingSellMode);
                                int total = unit * qty;

                                return name + " x" + qty + stackText +
                                        "\n 1 = " + formatCoin(unit) +
                                        " | total = " + formatCoin(total);
                            })
                            .toList()
                                          );

        });


        // ---------- Main content grid ----------
        HBox mainArea = new HBox(14, table, detailsCard);

        // let the table take all leftover horizontal space
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
        reloadTable.run();

        Platform.runLater(() -> {
            // Header background
            var header = table.lookup("TableHeaderRow");
            if (header != null) {
                header.setStyle("-fx-background-color: #0f1115;");
            }

            // Column header cells
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
        });

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

    private static TableCell<CraftRow, Number> coinCell(boolean signed) {
        return new TableCell<>() {
            @Override protected void updateItem(Number copper, boolean empty) {
                super.updateItem(copper, empty);
                if (empty || copper == null) {
                    setText(null);
                    return;
                }
                int v = copper.intValue();
                setText((signed ? signedCoin(v) : formatCoin(v)));
                setStyle("-fx-text-fill: white; -fx-font-family: 'Consolas';");
            }
        };
    }

    private static TableCell<CraftRow, Number> profitCell() {
        return new TableCell<>() {
            @Override protected void updateItem(Number copper, boolean empty) {
                super.updateItem(copper, empty);
                if (empty || copper == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                int v = copper.intValue();
                setText(signedCoin(v));
                // simple green/red effect
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

    private static String formatCoin(int copper) {
        int abs = Math.abs(copper);
        int g = abs / 10000;
        int s = (abs % 10000) / 100;
        int c = abs % 100;
        return g + "g " + s + "s " + c + "c";
    }

    private static String signedCoin(int copper) {
        if (copper == 0) return "0g 0s 0c";
        return (copper > 0 ? "+" : "-") + formatCoin(copper);
    }

    private static String trimDouble(double d) {
        if (Math.abs(d - Math.round(d)) < 1e-9) return String.valueOf((long) Math.round(d));
        return String.valueOf(d);
    }
    private static TreeItem<String> toTreeItem(Node n, CraftingProfitController controller) {
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


    private static int parseCoinToCopper(String text) {
        if (text == null) return 0;
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return 0;

        int g = 0, s = 0, c = 0;

        // allow formats: "20g", "50s", "10c", "1g 20s 5c"
        String[] parts = t.split("\\s+");
        for (String p : parts) {
            p = p.trim();
            if (p.endsWith("g")) g = Integer.parseInt(p.substring(0, p.length()-1));
            else if (p.endsWith("s")) s = Integer.parseInt(p.substring(0, p.length()-1));
            else if (p.endsWith("c")) c = Integer.parseInt(p.substring(0, p.length()-1));
            else {
                // fallback: if user typed plain number assume gold
                try { g = Integer.parseInt(p); } catch (Exception ignored) {}
            }
        }
        return g * 10000 + s * 100 + c;
    }

}
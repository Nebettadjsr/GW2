import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class EctoView {

    // --- HTTP + JSON ---
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- GW2 IDs ---
    private static final int ECTO_ID = 19721;
    private static final int DUST_ID = 24277;

    // Salvage kit IDs (provided by you)
    private static final int MASTERS_SALVAGE_KIT_ID = 23043;
    private static final int MYSTIC_SALVAGE_KIT_ID = 23045;
    private static final int SILVER_FED_SALVAGE_O_MATIC_ID = 67027;

    // --- Static assumptions (as requested) ---
    private static final double LUCK_PER_ECTO = 20.0;
    private static final double DUST_PER_ECTO = 0.75;
    private static final int ECTOS_PER_1000_LUCK = 50;

    // --- TP model (raw from /v2/commerce/prices) ---
    static class TpQuote {
        final int buyUnit;   // buys.unit_price  (you RECEIVE this if you instant-sell)
        final int sellUnit;  // sells.unit_price (you PAY this if you instant-buy)

        TpQuote(int buyUnit, int sellUnit) {
            this.buyUnit = buyUnit;
            this.sellUnit = sellUnit;
        }
    }

    // Meanings we want in UI (matches your terminal wording):
    // Instant Buy  = sells.unit_price
    // Listing Buy  = buys.unit_price
    // Instant Sell = buys.unit_price
    // Listing Sell = sells.unit_price
    private static int ectoInstantBuy(TpQuote ecto) { return ecto.sellUnit; }
    private static int ectoListingBuy(TpQuote ecto) { return ecto.buyUnit; }

    private static int dustInstantSell(TpQuote dust) { return dust.buyUnit; }
    private static int dustListingSell(TpQuote dust) { return dust.sellUnit; }

    public static void show(Stage stage, Runnable onBack) {

        // ---------------- UI: top ----------------
        Button btnBack = new Button("← Back");
        btnBack.setOnAction(e -> onBack.run());

        HBox topBar = new HBox(btnBack);
        topBar.setPadding(new Insets(10));

        Label title = new Label("Ecto Salvage Analyzer");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label statusLabel = new Label("Fetching Trading Post prices...");
        statusLabel.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        // ---------------- Base card (static) ----------------
        Label baseTitle = new Label("Base data");
        baseTitle.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");

        // kit pills (icon + name)
        ImageView kit1Icon = new ImageView();
        ImageView kit2Icon = new ImageView();
        ImageView kit3Icon = new ImageView();
        setupIconView(kit1Icon, 20);
        setupIconView(kit2Icon, 20);
        setupIconView(kit3Icon, 20);

        HBox kitsRow = new HBox(14,
                                kitPill(kit1Icon, "Master’s Salvage Kit"),
                                kitPill(kit2Icon, "Mystic Salvage Kit"),
                                kitPill(kit3Icon, "Silver-Fed Salvage-o-Matic")
        );
        kitsRow.setAlignment(Pos.CENTER_LEFT);

        HBox assumptionRow1 = row("1 ecto ≈ 20 Luck + ≈ 0,75 Dust");
        HBox assumptionRow2 = row("1000 Luck needs ≈ 50 Ectos");

        VBox baseCard = card(baseTitle, new LabelStyled("Salvage kits with 25% Chance:"), kitsRow, assumptionRow1, assumptionRow2);
        baseCard.setMaxWidth(760);

        // ---------------- Prices card ----------------
        Label pricesTitle = new Label("Live Trading Post prices");
        pricesTitle.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");

        ImageView ectoIcon = new ImageView();
        ImageView dustIcon = new ImageView();
        setupIconView(ectoIcon, 20);
        setupIconView(dustIcon, 20);

        Label ectoName = new Label("Ecto");
        Label dustName = new Label("Dust");
        ectoName.setStyle("-fx-text-fill: white;");
        dustName.setStyle("-fx-text-fill: white;");

        Label ectoInstantBuyLabel = value("-");
        Label ectoListingBuyLabel = value("-");
        Label dustInstantSellLabel = value("-");
        Label dustListingSellLabel = value("-");

        VBox pricesCard = card(
                pricesTitle,
                priceRow(ectoIcon, ectoName, "Instant Buy:", ectoInstantBuyLabel, "Listing Buy:", ectoListingBuyLabel),
                priceRow(dustIcon, dustName, "Instant Sell:", dustInstantSellLabel, "Listing Sell:", dustListingSellLabel)
                              );
        pricesCard.setMaxWidth(760);

        // ---------------- Tables ----------------
        Label t1Title = new Label("ECTO SALVAGE PROFIT");
        t1Title.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold; -fx-opacity: 0.95;");

        GridPane profitGrid = build2x2Grid(
                "Dust Instant Sell", "Dust Listing Sell",
                "Ecto Instant Buy", "Ecto Listing Buy"
                                          );

        Label t2Title = new Label("Cost per 1000 Luck ~ 50 ectos");
        t2Title.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold; -fx-opacity: 0.95;");

        GridPane luckGrid = build2x2Grid(
                "Dust Instant Sell", "Dust Listing Sell",
                "Ecto Instant Buy", "Ecto Listing Buy"
                                        );

        VBox tableCard1 = card(t1Title, profitGrid);
        VBox tableCard2 = card(t2Title, luckGrid);
        tableCard1.setMaxWidth(760);
        tableCard2.setMaxWidth(760);

        VBox center = new VBox(14, title, statusLabel, baseCard, pricesCard, tableCard1, tableCard2);
        center.setAlignment(Pos.TOP_CENTER);
        center.setPadding(new Insets(20, 20, 24, 20));

        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0f1115; -fx-background-color: #0f1115;");

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(scroll);
        root.setStyle("-fx-background-color: #0f1115;");

        stage.setScene(new Scene(root, 1280, 720));

        // ---------------- Fetch prices + icons on open ----------------
        Thread t = new Thread(() -> {
            try {
                // prices
                Map<Integer, TpQuote> quotes = fetchTpQuotes(ECTO_ID, DUST_ID);
                TpQuote ecto = quotes.get(ECTO_ID);
                TpQuote dust = quotes.get(DUST_ID);

                // icons (ecto+dust+kits)
                Map<Integer, Image> icons = fetchItemIcons(
                        ECTO_ID, DUST_ID,
                        MASTERS_SALVAGE_KIT_ID, MYSTIC_SALVAGE_KIT_ID, SILVER_FED_SALVAGE_O_MATIC_ID
                                                          );

                Platform.runLater(() -> {
                    if (ecto == null || dust == null) {
                        statusLabel.setText("❌ Failed to load prices (missing data)");
                        return;
                    }

                    // set item icons
                    Image eImg = icons.get(ECTO_ID);
                    Image dImg = icons.get(DUST_ID);
                    if (eImg != null) ectoIcon.setImage(eImg);
                    if (dImg != null) dustIcon.setImage(dImg);

                    // set kit icons
                    Image k1 = icons.get(MASTERS_SALVAGE_KIT_ID);
                    Image k2 = icons.get(MYSTIC_SALVAGE_KIT_ID);
                    Image k3 = icons.get(SILVER_FED_SALVAGE_O_MATIC_ID);
                    if (k1 != null) kit1Icon.setImage(k1);
                    if (k2 != null) kit2Icon.setImage(k2);
                    if (k3 != null) kit3Icon.setImage(k3);

                    // update price block
                    ectoInstantBuyLabel.setText(formatCoin(ectoInstantBuy(ecto)));
                    ectoListingBuyLabel.setText(formatCoin(ectoListingBuy(ecto)));
                    dustInstantSellLabel.setText(formatCoin(dustInstantSell(dust)));
                    dustListingSellLabel.setText(formatCoin(dustListingSell(dust)));

                    // fill tables
                    fillProfitGrid(profitGrid, ecto, dust);
                    fillLuckGrid(luckGrid, ecto, dust);

                    // timestamp status
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    statusLabel.setText("Prices fetched: " + LocalDateTime.now().format(fmt));
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("❌ Failed to load prices: " + ex.getMessage()));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // =========================
    // Tables
    // =========================

    private static void fillProfitGrid(GridPane grid, TpQuote ecto, TpQuote dust) {
        // Per 1 ecto:
        // profit = (dustSell * dustPerEcto) - ectoCost
        int ectoCostInstant = ectoInstantBuy(ecto);
        int ectoCostListing = ectoListingBuy(ecto);

        int dustRevInstant = (int) Math.round(dustInstantSell(dust) * DUST_PER_ECTO);
        int dustRevListing = (int) Math.round(dustListingSell(dust) * DUST_PER_ECTO);

        setCell(grid, 1, 1, signedCoin(dustRevInstant - ectoCostInstant));
        setCell(grid, 2, 1, signedCoin(dustRevListing - ectoCostInstant));
        setCell(grid, 1, 2, signedCoin(dustRevInstant - ectoCostListing));
        setCell(grid, 2, 2, signedCoin(dustRevListing - ectoCostListing));
    }

    private static void fillLuckGrid(GridPane grid, TpQuote ecto, TpQuote dust) {
        // Cost for 1000 luck (~50 ectos):
        // cost = ectoCost*50 - dustRevenue*50
        int ectoCostInstant = ectoInstantBuy(ecto);
        int ectoCostListing = ectoListingBuy(ecto);

        int dustRevInstant = (int) Math.round(dustInstantSell(dust) * DUST_PER_ECTO);
        int dustRevListing = (int) Math.round(dustListingSell(dust) * DUST_PER_ECTO);

        int n = ECTOS_PER_1000_LUCK;

        int c11 = ectoCostInstant * n - dustRevInstant * n;
        int c12 = ectoCostInstant * n - dustRevListing * n;
        int c21 = ectoCostListing * n - dustRevInstant * n;
        int c22 = ectoCostListing * n - dustRevListing * n;


        setCell(grid, 1, 1, formatCoin(c11) );
        setCell(grid, 2, 1, formatCoin(c12) );
        setCell(grid, 1, 2, formatCoin(c21) );
        setCell(grid, 2, 2, formatCoin(c22) );
    }

    // =========================
    // Fetching
    // =========================

    private static Map<Integer, TpQuote> fetchTpQuotes(int... itemIds) throws Exception {
        String idsParam = Arrays.stream(itemIds)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + idsParam;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200 && res.statusCode() != 206) {
            throw new RuntimeException("TP price fetch failed: HTTP " + res.statusCode());
        }

        JsonNode root = MAPPER.readTree(res.body());
        if (!root.isArray()) throw new RuntimeException("Unexpected TP JSON");

        Map<Integer, TpQuote> out = new HashMap<>();
        for (JsonNode p : root) {
            int id = p.get("id").asInt();
            JsonNode buys = p.get("buys");
            JsonNode sells = p.get("sells");

            if (buys == null || sells == null || buys.isNull() || sells.isNull()) continue;

            int buyUnit = buys.get("unit_price").asInt();
            int sellUnit = sells.get("unit_price").asInt();

            out.put(id, new TpQuote(buyUnit, sellUnit));
        }
        return out;
    }

    private static Map<Integer, Image> fetchItemIcons(int... itemIds) throws Exception {
        if (itemIds == null || itemIds.length == 0) return Collections.emptyMap();

        String idsParam = Arrays.stream(itemIds)
                .distinct()
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200 && res.statusCode() != 206) {
            return Collections.emptyMap(); // icons are nice-to-have
        }

        JsonNode root = MAPPER.readTree(res.body());
        if (!root.isArray()) return Collections.emptyMap();

        Map<Integer, Image> out = new HashMap<>();
        for (JsonNode item : root) {
            int id = item.get("id").asInt();
            if (item.has("icon") && !item.get("icon").isNull()) {
                out.put(id, new Image(item.get("icon").asText(), true));
            }
        }
        return out;
    }

    // =========================
    // UI helpers
    // =========================

    private static VBox card(javafx.scene.Node... children) {
        VBox v = new VBox(10, children);
        v.setPadding(new Insets(12));
        v.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                        "-fx-border-color: rgba(255,255,255,0.08);" +
                        "-fx-border-radius: 10;" +
                        "-fx-background-radius: 10;"
                  );
        return v;
    }

    // Small label helper (white, slightly faded)
    private static class LabelStyled extends Label {
        LabelStyled(String text) {
            super(text);
            setStyle("-fx-text-fill: white; -fx-opacity: 0.9;");
        }
    }

    private static HBox row(Object... parts) {
        HBox h = new HBox(10);
        h.setAlignment(Pos.CENTER_LEFT);

        for (Object p : parts) {
            if (p instanceof String s) {
                Label l = new Label(s);
                l.setStyle("-fx-text-fill: white; -fx-opacity: 0.9;");
                h.getChildren().add(l);
            } else if (p instanceof javafx.scene.Node n) {
                h.getChildren().add(n);
            }
        }
        return h;
    }

    private static Label value(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        return l;
    }

    private static void setupIconView(ImageView iv, int size) {
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
    }

    private static HBox kitPill(ImageView icon, String name) {
        Label l = new Label(name);
        l.setStyle("-fx-text-fill: white; -fx-opacity: 0.95;");

        HBox h = new HBox(8, icon, l);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(6, 10, 6, 10));
        h.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                        "-fx-border-color: rgba(255,255,255,0.08);" +
                        "-fx-border-radius: 999;" +
                        "-fx-background-radius: 999;"
                  );
        return h;
    }

    private static HBox priceRow(ImageView icon, Label name,
                                 String k1, Label v1,
                                 String k2, Label v2) {

        Label k1L = new Label(k1);
        Label k2L = new Label(k2);
        k1L.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");
        k2L.setStyle("-fx-text-fill: white; -fx-opacity: 0.85;");

        HBox left = new HBox(8, icon, name);
        left.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(14,
                            left,
                            spacer,
                            k1L, v1,
                            new Label("   "),
                            k2L, v2
        );
        row.setAlignment(Pos.CENTER_LEFT);

        // hide that blank label
        for (javafx.scene.Node n : row.getChildren()) {
            if (n instanceof Label l && "   ".equals(l.getText())) {
                l.setStyle("-fx-text-fill: transparent;");
            }
        }
        return row;
    }

    private static GridPane build2x2Grid(String col1, String col2, String row1, String row2) {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.setPadding(new Insets(6, 0, 0, 0));

        g.add(headerCell(""), 0, 0);
        g.add(headerCell(col1), 1, 0);
        g.add(headerCell(col2), 2, 0);

        g.add(headerCell(row1), 0, 1);
        g.add(headerCell(row2), 0, 2);

        g.add(dataCell("-"), 1, 1);
        g.add(dataCell("-"), 2, 1);
        g.add(dataCell("-"), 1, 2);
        g.add(dataCell("-"), 2, 2);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(160);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(260);

        ColumnConstraints c2 = new ColumnConstraints();
        c2.setMinWidth(260);

        g.getColumnConstraints().addAll(c0, c1, c2);
        return g;
    }

    private static StackPane headerCell(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-opacity: 0.9;");
        StackPane p = new StackPane(l);
        p.setAlignment(Pos.CENTER_LEFT);
        p.setPadding(new Insets(8));
        p.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                        "-fx-border-color: rgba(255,255,255,0.07);" +
                        "-fx-border-radius: 8;" +
                        "-fx-background-radius: 8;"
                  );
        return p;
    }

    private static StackPane dataCell(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: white; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
        StackPane p = new StackPane(l);
        p.setAlignment(Pos.CENTER_LEFT);
        p.setPadding(new Insets(10));
        p.setStyle(
                "-fx-background-color: rgba(0,0,0,0.25);" +
                        "-fx-border-color: rgba(255,255,255,0.07);" +
                        "-fx-border-radius: 8;" +
                        "-fx-background-radius: 8;"
                  );
        return p;
    }

    private static void setCell(GridPane g, int col, int row, String text) {
        javafx.scene.Node n = getNodeFromGrid(g, col, row);
        if (n instanceof StackPane sp && sp.getChildren().size() == 1 && sp.getChildren().get(0) instanceof Label l) {
            l.setText(text);
        }
    }

    private static javafx.scene.Node getNodeFromGrid(GridPane gridPane, int col, int row) {
        for (javafx.scene.Node node : gridPane.getChildren()) {
            Integer c = GridPane.getColumnIndex(node);
            Integer r = GridPane.getRowIndex(node);
            int cc = (c == null) ? 0 : c;
            int rr = (r == null) ? 0 : r;
            if (cc == col && rr == row) return node;
        }
        return null;
    }

    // =========================
    // Formatting
    // =========================

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

}
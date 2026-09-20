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
import util.CoinUtils;

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
    private static final double LUCK_PER_ECTO = EctoSalvageCalculator.LUCK_PER_ECTO;
    private static final double DUST_PER_ECTO = EctoSalvageCalculator.DUST_PER_ECTO;
    private static final int ECTOS_PER_1000_LUCK = EctoSalvageCalculator.ECTOS_PER_1000_LUCK;

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

        Label feeNoticeLabel = new Label(
                "Note: figures below already deduct the Trading Post's 15% selling fee from recovered " +
                        "Dust's sale proceeds. Ecto acquisition costs and Luck amounts are unaffected. " +
                        "\"Net\" prices are proceeds after that fee; other prices are raw Trading Post quotes.");
        feeNoticeLabel.setWrapText(true);
        feeNoticeLabel.getStyleClass().add("ecto-fee-notice");
        feeNoticeLabel.setStyle(
                "-fx-text-fill: #f0c060; -fx-background-color: rgba(240,192,96,0.10);" +
                        "-fx-border-color: rgba(240,192,96,0.35); -fx-border-radius: 6;" +
                        "-fx-background-radius: 6; -fx-padding: 8;");

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
        Label dustInstantSellNetLabel = value("-");
        Label dustListingSellNetLabel = value("-");

        VBox pricesCard = card(
                pricesTitle,
                priceRow(ectoIcon, ectoName, "Instant Buy:", ectoInstantBuyLabel, "Listing Buy:", ectoListingBuyLabel),
                priceRow(dustIcon, dustName, "Instant Sell (raw):", dustInstantSellLabel, "Listing Sell (raw):", dustListingSellLabel),
                priceRow(new ImageView(), new Label(""), "Instant Sell (net of TP fee):", dustInstantSellNetLabel, "Listing Sell (net of TP fee):", dustListingSellNetLabel)
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

        VBox center = new VBox(14, title, statusLabel, feeNoticeLabel, baseCard, pricesCard, tableCard1, tableCard2);
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
                    ectoInstantBuyLabel.setText(CoinUtils.format(ectoInstantBuy(ecto)));
                    ectoListingBuyLabel.setText(CoinUtils.format(ectoListingBuy(ecto)));
                    dustInstantSellLabel.setText(CoinUtils.format(dustInstantSell(dust)));
                    dustListingSellLabel.setText(CoinUtils.format(dustListingSell(dust)));
                    dustInstantSellNetLabel.setText(CoinUtils.format(EctoSalvageCalculator.netSaleProceeds(dustInstantSell(dust))));
                    dustListingSellNetLabel.setText(CoinUtils.format(EctoSalvageCalculator.netSaleProceeds(dustListingSell(dust))));

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
        // Per 1 ecto, net of the Trading Post's 15% selling fee on recovered Dust (DOMAIN_SPEC.md §46).
        var instantBuyInstantSell = EctoSalvageCalculator.evaluate(ectoInstantBuy(ecto), dustInstantSell(dust));
        var instantBuyListingSell = EctoSalvageCalculator.evaluate(ectoInstantBuy(ecto), dustListingSell(dust));
        var listingBuyInstantSell = EctoSalvageCalculator.evaluate(ectoListingBuy(ecto), dustInstantSell(dust));
        var listingBuyListingSell = EctoSalvageCalculator.evaluate(ectoListingBuy(ecto), dustListingSell(dust));

        setCell(grid, 1, 1, CoinUtils.formatSigned(instantBuyInstantSell.profitPerEcto()));
        setCell(grid, 2, 1, CoinUtils.formatSigned(instantBuyListingSell.profitPerEcto()));
        setCell(grid, 1, 2, CoinUtils.formatSigned(listingBuyInstantSell.profitPerEcto()));
        setCell(grid, 2, 2, CoinUtils.formatSigned(listingBuyListingSell.profitPerEcto()));
    }

    private static void fillLuckGrid(GridPane grid, TpQuote ecto, TpQuote dust) {
        // Cost for 1000 Luck (~50 ectos), derived from the same fee-inclusive net cost (DOMAIN_SPEC.md §47).
        var instantBuyInstantSell = EctoSalvageCalculator.evaluate(ectoInstantBuy(ecto), dustInstantSell(dust));
        var instantBuyListingSell = EctoSalvageCalculator.evaluate(ectoInstantBuy(ecto), dustListingSell(dust));
        var listingBuyInstantSell = EctoSalvageCalculator.evaluate(ectoListingBuy(ecto), dustInstantSell(dust));
        var listingBuyListingSell = EctoSalvageCalculator.evaluate(ectoListingBuy(ecto), dustListingSell(dust));

        setCell(grid, 1, 1, CoinUtils.format(instantBuyInstantSell.costPer1000Luck()));
        setCell(grid, 2, 1, CoinUtils.format(instantBuyListingSell.costPer1000Luck()));
        setCell(grid, 1, 2, CoinUtils.format(listingBuyInstantSell.costPer1000Luck()));
        setCell(grid, 2, 2, CoinUtils.format(listingBuyListingSell.costPer1000Luck()));
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
}
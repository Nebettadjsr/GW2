package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import repo.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Persists the public currency catalog's display metadata (name, image source) for wallet currencies. */
public final class CurrencySync {
    private CurrencySync() {}

    static final String URL = "https://api.guildwars2.com/v2/currencies?ids=all";

    record Currency(int id, String name, String iconUrl) {}

    public static void syncCurrencies() throws Exception {
        syncCurrencies(Gw2ApiClient::getPublicArray);
    }

    static void syncCurrencies(PublicArrayFetcher fetcher) throws Exception {
        List<Currency> currencies = parse(fetcher.get(URL));
        if (currencies.isEmpty()) return;
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                persist(con, currencies);
                con.commit();
            } catch (Exception failure) {
                con.rollback();
                throw failure;
            }
        }
    }

    /** Records without a positive integer id or a name are ignored; an image source is optional. */
    static List<Currency> parse(JsonNode root) {
        List<Currency> currencies = new ArrayList<>();
        if (root == null || !root.isArray()) return currencies;
        for (JsonNode node : root) {
            JsonNode id = node.path("id");
            JsonNode name = node.path("name");
            if (!id.isInt() || id.intValue() <= 0 || !name.isTextual() || name.asText().isBlank()) continue;
            JsonNode icon = node.path("icon");
            currencies.add(new Currency(id.intValue(), name.asText(), icon.isTextual() ? icon.asText() : null));
        }
        return currencies;
    }

    static void persist(Connection con, List<Currency> currencies) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO wallet_currencies (currency_id, name, icon_url, fetched_at) VALUES (?, ?, ?, now())
                ON CONFLICT (currency_id) DO UPDATE SET name = EXCLUDED.name, icon_url = EXCLUDED.icon_url,
                    fetched_at = EXCLUDED.fetched_at
                """)) {
            for (Currency currency : currencies) {
                ps.setInt(1, currency.id());
                ps.setString(2, currency.name());
                ps.setString(3, currency.iconUrl());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}

package sync;

import repo.Db;
import sync.tp.relevance.CraftingProfitItemCollector;
import util.TpPrice;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Profit price refresh with its upstream HTTP call replaced, and nothing else
 * (STORY-APP-014 acceptance criterion 6).
 *
 * <p>Each run does exactly what {@code TpSync.syncTpPricesForProfit} does: it asks the shared
 * {@link CraftingProfitItemCollector} which relevant tradeable items are stale or missing, then
 * writes the answers through the shared {@code TpSync} upsert — the same statement, so an item the
 * market has no quote for still gets the explicit all-null row that makes it covered, and no absent
 * price is turned into a zero one. What it does not do is call the Guild Wars 2 API: the quotes are
 * stated by the test instead.
 *
 * <p>Because the selection is the production one, {@link #passes()} is the set of ids a run actually
 * refreshed, which is what lets a coordination test assert coverage of changing required sets rather
 * than merely counting runs. {@link #gate(Runnable)} runs between a pass's selection and its write,
 * so a test can hold a run open at a known point or change the account inputs that relevance is
 * derived from while it is in flight.
 *
 * <p>Every connection comes from {@link Db#open()}, so it obeys {@link Db#TEST_SCHEMA_PROPERTY} like
 * the production path does and touches no schema the test did not create.
 */
public class ProfitQuoteRefreshRecorder extends TradingPostPriceRefreshGateway {

    private final List<Set<Integer>> passes = Collections.synchronizedList(new ArrayList<>());
    private final Set<Integer> withoutMarket = Collections.synchronizedSet(new TreeSet<>());

    private volatile Runnable gate = () -> { };
    private volatile boolean failNext;
    private volatile int discoveryRefreshes;

    /** Ids the simulated upstream answers "no market data" for, as the real API does. */
    public void answersNoMarketDataFor(Integer... itemIds) {
        Collections.addAll(withoutMarket, itemIds);
    }

    /** Runs inside each pass, after it selected its ids and before it writes them. */
    public void gate(Runnable gate) {
        this.gate = gate;
    }

    public void failNextRefresh() {
        failNext = true;
    }

    /** The ids each completed selection asked for, oldest pass first. */
    public List<Set<Integer>> passes() {
        synchronized (passes) {
            return List.copyOf(passes);
        }
    }

    public int discoveryRefreshes() {
        return discoveryRefreshes;
    }

    @Override
    public void syncTpPricesForProfit() throws Exception {
        Set<Integer> ids;
        try (Connection con = Db.open()) {
            ids = new TreeSet<>(new CraftingProfitItemCollector().collect(con));
        }
        passes.add(Set.copyOf(ids));

        gate.run();

        if (failNext) {
            failNext = false;
            throw new IllegalStateException("the simulated upstream refused the quote batch");
        }
        if (ids.isEmpty()) return;

        List<TpPrice> quotes = new ArrayList<>(ids.size());
        for (Integer itemId : ids) {
            quotes.add(withoutMarket.contains(itemId)
                    ? TpPrice.noData(itemId)
                    : new TpPrice(itemId, 100L, 10 + itemId, 200L, 40 + itemId));
        }

        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                TpSync.upsertTpPrices(con, quotes, new Timestamp(System.currentTimeMillis()));
                con.commit();
            } catch (Exception failure) {
                con.rollback();
                throw failure;
            }
        }
    }

    @Override
    public void syncTpPricesForDiscovery() {
        discoveryRefreshes++;
    }
}

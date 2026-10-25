package application;

import model.AccountScope;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;
import sync.tp.relevance.CraftingProfitItemCollector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The input versions one Crafting Profit calculation was captured against, and the two readers that
 * produce them (TARGET_ARCHITECTURE.md §9.1).
 *
 * <p>Both readers take the calculation's own {@link ProfitInputSession} connection, so the versions
 * reported come from the same database snapshot the calculation's data came from. Reading them
 * publishes nothing: no marker is written or advanced here, by a read or by task admission.
 */
public final class CalculationInputVersions {

    /**
     * The account sources Crafting Profit calculates from. Account Luck is deliberately absent: the
     * calculation does not use it, so requiring it would make the page wait on an unrelated fetch.
     */
    public static final Set<String> REQUIRED_ACCOUNT_SOURCES = Set.of(
            AccountSyncStateRepository.BANK,
            AccountSyncStateRepository.MATERIALS,
            AccountSyncStateRepository.RECIPES,
            AccountSyncStateRepository.CHARACTERS);

    /** Reads the required sources' last successful completion instants. */
    public interface AccountSourceVersionReader {
        /**
         * @param con the calculation's pinned connection, or null for a detached session
         * @return completion instant per source; a source with no successful run is absent
         */
        Map<String, Instant> read(Connection con, Set<String> sources) throws SQLException;
    }

    /** Reads how much of the feature-relevant tradeable item set the shared quote cache answers. */
    public interface PriceCoverageReader {
        CalculationFreshness.PriceCoverage read(Connection con) throws SQLException;

        /**
         * The same report for one account's required item set (STORY-APP-015).
         *
         * <p>Only the <em>required set</em> is account-derived - it comes from this account's recipe
         * knowledge. The quotes themselves stay global and shared, so two accounts requiring the same
         * item read the same quote and the same version.
         *
         * <p>The default delegates to the connection-only read, which is right for the in-memory
         * fakes application-layer tests supply: they answer from their own state and ignore the
         * connection entirely. The one implementation that reaches the database,
         * {@link DatabasePriceCoverageReader}, overrides it - because there the unscoped query is
         * refused on the versioned account schema rather than quietly answering for every account.
         */
        default CalculationFreshness.PriceCoverage read(Connection con, AccountScope scope) throws SQLException {
            return read(con);
        }
    }

    /**
     * Reads one account's source markers, completion instant <em>and</em> generation
     * (STORY-APP-015, TARGET_ARCHITECTURE.md §7.2 "Freshness envelopes identify the captured account
     * and source generations, never later live markers").
     *
     * <p>Separate from {@link AccountSourceVersionReader} because a generation is what a completion
     * instant cannot be: two publications of an unchanged payload may record the same instant, and a
     * timestamp is explicitly not a generation identifier - so the captured identity has to carry
     * the number the publication advanced.
     */
    public interface AccountSourceStateReader {
        /**
         * @param con    the calculation's pinned connection; never null for a scoped session
         * @param scope  the account whose markers are read
         * @return state per source; a source with no successful publication is absent
         */
        Map<String, AccountSourceStateRepository.SourceState> read(Connection con,
                                                                   AccountScope scope,
                                                                   Set<String> sources) throws SQLException;
    }

    private final String accountId;
    private final Map<String, Instant> accountVersions;
    private final Map<String, Long> accountGenerations;
    private final CalculationFreshness.PriceCoverage coverage;
    private final Duration accountMaxAge;
    private final Instant evaluatedAt;

    public CalculationInputVersions(Map<String, Instant> accountVersions,
                                    CalculationFreshness.PriceCoverage coverage,
                                    Duration accountMaxAge,
                                    Instant evaluatedAt) {
        this(null, accountVersions, Map.of(), coverage, accountMaxAge, evaluatedAt);
    }

    /**
     * The captured versions of one account's inputs (STORY-APP-015).
     *
     * @param accountId          the account this capture belongs to, or null for an unscoped capture
     * @param accountGenerations the generation actually read per source, for the sources that had one
     */
    public CalculationInputVersions(String accountId,
                                    Map<String, Instant> accountVersions,
                                    Map<String, Long> accountGenerations,
                                    CalculationFreshness.PriceCoverage coverage,
                                    Duration accountMaxAge,
                                    Instant evaluatedAt) {
        this.accountId = accountId;
        this.accountVersions = Map.copyOf(accountVersions);
        this.accountGenerations = Map.copyOf(accountGenerations);
        this.coverage = coverage;
        this.accountMaxAge = accountMaxAge;
        this.evaluatedAt = evaluatedAt;
    }

    /** The account this capture belongs to, or null when it was taken through an unscoped session. */
    public String accountId() {
        return accountId;
    }

    /** The generation each required source had when this capture read it. */
    public Map<String, Long> accountGenerations() {
        return accountGenerations;
    }

    public CalculationFreshness.PriceCoverage coverage() {
        return coverage;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }

    /**
     * Whether a calculation can be performed from these versions at all.
     *
     * <p>Expiry is not a reason to refuse: an expired source still holds its last successfully
     * published data. A source that never completed is, because there is no data to calculate from
     * and inventing an empty one would silently answer "you own nothing". A source that completed
     * and contained nothing is usable, since synchronized emptiness is a real answer.
     */
    public boolean usable() {
        for (String source : REQUIRED_ACCOUNT_SOURCES) {
            if (accountVersions.get(source) == null) return false;
        }
        return true;
    }

    /** The required sources whose state is not {@link CalculationFreshness.SourceState#FRESH}. */
    public Set<String> sourcesNeedingRefresh() {
        Set<String> needing = new LinkedHashSet<>();
        for (CalculationFreshness.SourceFreshness source : accountSources()) {
            if (source.state() != CalculationFreshness.SourceState.FRESH) needing.add(source.source());
        }
        return needing;
    }

    /** Per-source state, in a stable order so two captures of one generation report identically. */
    public List<CalculationFreshness.SourceFreshness> accountSources() {
        List<CalculationFreshness.SourceFreshness> states = new ArrayList<>();
        for (String source : orderedSources()) {
            Instant fetchedAt = accountVersions.get(source);
            states.add(new CalculationFreshness.SourceFreshness(source, fetchedAt, stateOf(fetchedAt)));
        }
        return List.copyOf(states);
    }

    private CalculationFreshness.SourceState stateOf(Instant fetchedAt) {
        if (fetchedAt == null) return CalculationFreshness.SourceState.MISSING;
        return AccountDataFreshnessPolicy.isStale(java.util.Optional.of(fetchedAt), evaluatedAt, accountMaxAge)
                ? CalculationFreshness.SourceState.STALE
                : CalculationFreshness.SourceState.FRESH;
    }

    /** Every required account source is fresh and every required quote is inside the TP policy. */
    public boolean confirmedCurrent() {
        return sourcesNeedingRefresh().isEmpty() && coverage.covered();
    }

    /**
     * Stable identity of this input generation: the required sources' completion instants, the
     * required item set's coverage boundaries and the version of the relevant quotes themselves.
     * Two captures that saw the same published versions produce the same value, and any change to an
     * account version, to the required item set or to <em>any</em> relevant quote produces a
     * different one — which is what lets a detail response be recognised as explaining a different
     * generation than a row.
     *
     * <p>The quote version is what makes that true of a change the aggregates cannot see. A refresh
     * that refetches part of the required set leaves the counts and the oldest/newest instants
     * untouched whenever the refetched items are not the extremes, and a quote rewritten with new
     * numbers under the same instant changes no aggregate at all; both change the numbers the
     * calculation read, so both must change its identity. Without it, newer resolution detail could
     * be reported as a fresh explanation of an older row.
     *
     * <p>Not a secret and not a snapshot that can be retrieved: it names a generation, it does not
     * store one.
     */
    public String calculationId() {
        StringBuilder material = new StringBuilder();
        // The account is part of the identity, not metadata beside it: two accounts whose sources
        // happen to have completed at the same instants are different input generations, and a
        // detail computed for one must never be reported as explaining the other's row.
        material.append("account=").append(accountId).append(';');
        for (String source : orderedSources()) {
            material.append(source).append('=').append(accountVersions.get(source))
                    .append('#').append(accountGenerations.get(source)).append(';');
        }
        material.append("tp.required=").append(coverage.requiredItems()).append(';')
                .append("tp.fresh=").append(coverage.freshItems()).append(';')
                .append("tp.stale=").append(coverage.staleItems()).append(';')
                .append("tp.missing=").append(coverage.missingItems()).append(';')
                .append("tp.oldest=").append(coverage.oldestFetchedAt()).append(';')
                .append("tp.newest=").append(coverage.newestFetchedAt()).append(';')
                .append("tp.quotes=").append(coverage.quoteVersion());
        return digest(material.toString());
    }

    private static List<String> orderedSources() {
        List<String> ordered = new ArrayList<>(REQUIRED_ACCOUNT_SOURCES);
        ordered.sort(String::compareTo);
        return ordered;
    }

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    /**
     * Reads one account's markers through {@code repo.AccountSourceStateRepository} (STORY-APP-015).
     *
     * <p>One statement for every required source, issued on the calculation's own connection, so
     * every marker comes from the same point of the same snapshot as the data. A pure {@code SELECT}:
     * capturing freshness never creates it.
     */
    public static final class DatabaseAccountSourceStateReader implements AccountSourceStateReader {

        private final AccountSourceStateRepository markers = new AccountSourceStateRepository();

        @Override
        public Map<String, AccountSourceStateRepository.SourceState> read(Connection con,
                                                                          AccountScope scope,
                                                                          Set<String> sources)
                throws SQLException {
            if (con == null) return Map.of();
            return markers.readAll(con, scope, sources);
        }
    }

    /** Reads the markers through {@code repo.AccountSyncStateRepository}. */
    public static final class DatabaseAccountSourceVersionReader implements AccountSourceVersionReader {

        private final AccountSyncStateRepository markers = new AccountSyncStateRepository();

        @Override
        public Map<String, Instant> read(Connection con, Set<String> sources) throws SQLException {
            Map<String, Instant> versions = new LinkedHashMap<>();
            if (con == null) return versions;
            for (String source : sources) {
                markers.lookup(con, source).ifPresent(instant -> versions.put(source, instant));
            }
            return versions;
        }
    }

    /**
     * Reads coverage through the same relevance definition the refresh selects by, so a reported gap
     * and the work admitted to close it can never describe different item sets.
     */
    public static final class DatabasePriceCoverageReader implements PriceCoverageReader {

        private final CraftingProfitItemCollector collector = new CraftingProfitItemCollector();

        @Override
        public CalculationFreshness.PriceCoverage read(Connection con) throws SQLException {
            if (con == null) return CalculationFreshness.PriceCoverage.none();
            return map(collector.coverage(con));
        }

        @Override
        public CalculationFreshness.PriceCoverage read(Connection con, AccountScope scope) throws SQLException {
            if (con == null) return CalculationFreshness.PriceCoverage.none();
            return map(collector.coverage(con, scope));
        }

        private static CalculationFreshness.PriceCoverage map(CraftingProfitItemCollector.Coverage coverage) {
            return new CalculationFreshness.PriceCoverage(
                    coverage.requiredItems(),
                    coverage.freshItems(),
                    coverage.staleItems(),
                    coverage.missingItems(),
                    coverage.oldestFetchedAt(),
                    coverage.newestFetchedAt(),
                    coverage.covered(),
                    coverage.quoteVersion());
        }
    }
}

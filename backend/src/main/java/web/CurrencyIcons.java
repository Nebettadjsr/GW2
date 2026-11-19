package web;

import application.icons.ResourceIconUrls;
import model.BuildResourceType;
import repo.BuildMetadataRepository;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Same-origin icon URL of a wallet currency ({@code /api/resources/currencies/{id}/icon/...}), built from the
 * retained image source of the synchronized currency catalog. Never an upstream address; empty when no source
 * is retained (the browser then shows its neutral placeholder). Retained sources change rarely, so found sources
 * are remembered; a missing one is looked up again next time.
 */
final class CurrencyIcons {

    private CurrencyIcons() {}

    /** Test seam: where the retained source comes from. */
    static volatile IntFunction<Optional<String>> sources = id -> {
        try {
            return new BuildMetadataRepository().findIconSource(BuildResourceType.CURRENCIES, id);
        } catch (SQLException | RuntimeException unavailable) {
            return Optional.empty();
        }
    };

    private static final ConcurrentHashMap<Integer, String> FOUND = new ConcurrentHashMap<>();

    static String urlFor(int currencyId) {
        String source = FOUND.get(currencyId);
        if (source == null) {
            source = sources.apply(currencyId).orElse(null);
            if (source == null) return null;
            FOUND.put(currencyId, source);
        }
        return ResourceIconUrls.iconUrlFor(BuildResourceType.CURRENCIES, currencyId, source);
    }

    /** Forgets remembered sources (tests). */
    static void reset() {
        FOUND.clear();
    }
}

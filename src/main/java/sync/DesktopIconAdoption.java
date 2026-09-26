package sync;

import infra.icons.IconAcquisition;
import infra.icons.IconAcquisitionResult;
import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import infra.icons.IconStorageException;
import infra.icons.IconStore;
import infra.icons.StoredIcon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Gives one item a shared, keyed icon file for {@code items.icon_path} (TARGET_ARCHITECTURE.md §12.1).
 *
 * <p>This is how the desktop sync stopped being a second download store: it publishes through the same
 * {@link IconStore} and {@link IconAcquisition} the web image endpoint's misses use, so a file either
 * side acquired is immediately a hit for the other, and neither can write an entry the other would
 * consider partial.
 *
 * <p>Legacy {@code items/{itemId}.png} files are handled the way §12.1 requires. {@code IconSync}
 * recorded no source-version provenance for them and used {@code .png} names even for {@code .jpg}
 * sources, so their bytes are never trusted into the keyed cache on the strength of the current
 * database URL and filename. The only evidence available is byte equality with a successful on-demand
 * fetch of the canonical source, which {@link #adopt} reports as {@link Outcome#ADOPTED_LEGACY}. Either
 * way the legacy file is left in place and stays usable, and the keyed entry holds canonical bytes.
 *
 * <p>Nothing here reads or writes the database: the caller owns the {@code icon_path} update, which
 * therefore always happens after publication. A failure of that update cannot invalidate a committed
 * asset, and the next run finds the entry already committed instead of fetching it again.
 */
public final class DesktopIconAdoption {

    /** Where {@code IconSync.syncItemIconsToDisk} historically wrote {@code {itemId}.png}. */
    public static final String LEGACY_ITEMS_DIRECTORY = "items";

    /** What one item's adoption established. */
    public enum Outcome {
        /** The keyed entry already existed - no upstream request was made. */
        ALREADY_PUBLISHED,
        /** The canonical image was fetched and published for the first time. */
        PUBLISHED_FROM_UPSTREAM,
        /** As above, and a legacy desktop file proved byte-identical to the canonical image. */
        ADOPTED_LEGACY,
        /** The retained metadata is absent or not an accepted canonical source; nothing was fetched. */
        REJECTED_SOURCE,
        /** The canonical image does not exist upstream. */
        UPSTREAM_MISSING,
        /** A bounded failure: upstream, capacity or storage. Any legacy file is untouched and usable. */
        UNAVAILABLE
    }

    /**
     * @param file the published entry {@code items.icon_path} should name, or null when there is none
     */
    public record Adoption(Outcome outcome, Path file) {

        static Adoption of(Outcome outcome) {
            return new Adoption(outcome, null);
        }
    }

    private final Path legacyItemsDir;
    private final IconStore store;
    private final IconAcquisition acquisition;

    /**
     * @param iconCacheDir the configured {@code ICON_CACHE_DIR} root; the legacy files sit in its
     *                     {@value #LEGACY_ITEMS_DIRECTORY} subdirectory
     */
    public DesktopIconAdoption(Path iconCacheDir, IconStore store, IconAcquisition acquisition) {
        this.legacyItemsDir = iconCacheDir.resolve(LEGACY_ITEMS_DIRECTORY);
        this.store = store;
        this.acquisition = acquisition;
    }

    /**
     * Ensures a keyed entry exists for this item's retained source and reports the local file JavaFX
     * should be pointed at.
     *
     * @param rawIconSource retained {@code items.icon_url} metadata
     */
    public Adoption adopt(int itemId, String rawIconSource) {
        Optional<IconSource> accepted = IconSourcePolicy.accept(rawIconSource);
        if (accepted.isEmpty()) return Adoption.of(Outcome.REJECTED_SOURCE);

        IconSource source = accepted.get();

        Optional<StoredIcon> committed;
        try {
            committed = store.find(source.sourceKey(), source.extension());
        } catch (IconStorageException storageFailure) {
            return Adoption.of(Outcome.UNAVAILABLE);
        }

        if (committed.isPresent()) {
            return new Adoption(Outcome.ALREADY_PUBLISHED, committed.get().file());
        }

        return switch (acquisition.acquire(source)) {
            case IconAcquisitionResult.Available(StoredIcon icon) ->
                    new Adoption(legacyOutcomeFor(itemId, icon), icon.file());
            case IconAcquisitionResult.UpstreamMissing ignored -> Adoption.of(Outcome.UPSTREAM_MISSING);
            case IconAcquisitionResult.Unavailable ignored -> Adoption.of(Outcome.UNAVAILABLE);
        };
    }

    /**
     * Whether the item's legacy desktop file is provably the same asset as the canonical image. Only
     * byte equality establishes that; an unequal or unreadable legacy file simply is not adopted, and
     * its bytes never become the keyed entry.
     */
    private Outcome legacyOutcomeFor(int itemId, StoredIcon published) {
        Path legacy = legacyItemsDir.resolve(itemId + ".png");

        try {
            if (!Files.isRegularFile(legacy)) return Outcome.PUBLISHED_FROM_UPSTREAM;

            byte[] legacyBytes = Files.readAllBytes(legacy);
            return Arrays.equals(legacyBytes, published.bytes())
                    ? Outcome.ADOPTED_LEGACY
                    : Outcome.PUBLISHED_FROM_UPSTREAM;
        } catch (IOException unreadableLegacyFile) {
            return Outcome.PUBLISHED_FROM_UPSTREAM;
        }
    }
}

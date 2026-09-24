package application;

import repo.CharacterRepository;

import java.sql.SQLException;
import java.util.List;

/**
 * Application-layer use case for the crafting views' character-selector reads
 * ("GetCraftingCharacterOptions" / "GetCharacterNames", TARGET_ARCHITECTURE.md §8,
 * STORY-APP-010): returns the synced crafting-discipline rows and character names both crafting
 * selectors are populated from. Holds no JavaFX dependency and performs no calculation; it
 * coordinates one persistence collaborator. {@code CraftingProfitView} and
 * {@code CraftingDiscoveryView} are its callers.
 *
 * <p>Read-only: no synchronization and no database write happens here or in
 * {@link CharacterRepository}. A {@link SQLException} propagates unchanged, so each view keeps
 * deciding how to present a selector-load failure.
 *
 * <p>Deliberately not merged into {@code CraftingProfitService}/{@code CraftingDiscoveryService}:
 * those own one calculation each, whereas both views need the same selector read, and the read must
 * not be coupled to a calculation reload.
 */
public class CharacterSelectionService {

    private final CharacterRepository charRepo;

    public CharacterSelectionService() {
        this(new CharacterRepository());
    }

    /** Seam used by application-layer tests to substitute a fake repository (TARGET_ARCHITECTURE.md §25). */
    public CharacterSelectionService(CharacterRepository charRepo) {
        this.charRepo = charRepo;
    }

    /**
     * Every synced character's crafting disciplines with their ratings, in the repository's order
     * (discipline, then rating descending, then character name); empty when nothing is synced.
     * Both crafting views turn these rows into their {@code DiscChoice} selector entries.
     */
    public List<CharacterRepository.DiscRow> getCraftingCharacterOptions() throws SQLException {
        return charRepo.loadAllCharacterCrafting();
    }

    /**
     * Every synced character name ordered by name; empty when nothing is synced. Populates
     * Discovery's separate Character selector, which feeds the binding-aware owned-inventory
     * lookup (DOMAIN_SPEC.md section 11.1 / DQ-007).
     */
    public List<String> getCharacterNames() throws SQLException {
        return charRepo.loadAllCharacterNames();
    }
}

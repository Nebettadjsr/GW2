package application;

import model.AccountScope;
import repo.CharacterRepository;

import java.sql.SQLException;
import java.util.List;

/**
 * Character-selector read for the crafting views: one account's synced crafting-discipline rows,
 * which both crafting selectors are populated from. Cached Profit and Discovery validate selected
 * character names against the account roster directly, not through this use case. It performs no
 * calculation and coordinates one persistence collaborator; {@code CraftingSelectorOptionsApiController} is its
 * caller.
 *
 * <p>Read-only: no synchronization and no database write happens here or in
 * {@link CharacterRepository}. A {@link SQLException} propagates unchanged, so the caller keeps
 * deciding how to present a selector-load failure.
 *
 * <p>Deliberately not merged into {@code CraftingProfitService}/{@code CraftingDiscoveryService}:
 * those own one calculation each, whereas both need the same selector read, and the read must not
 * be coupled to a calculation reload.
 */
public class CharacterSelectionService {

    private final CharacterRepository charRepo;

    public CharacterSelectionService() {
        this(new CharacterRepository());
    }

    /** Seam used by application-layer tests to substitute a fake repository. */
    public CharacterSelectionService(CharacterRepository charRepo) {
        this.charRepo = charRepo;
    }

    /**
     * One account's crafting-discipline rows (STORY-APP-015). The scope is passed in by the boundary
     * that authorized the request; this use case has no account of its own to fall back to.
     */
    public List<CharacterRepository.DiscRow> getCraftingCharacterOptions(AccountScope scope) throws SQLException {
        return charRepo.loadAllCharacterCrafting(scope);
    }
}

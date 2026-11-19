package repo;

/**
 * Names of the account data sources whose completion is recorded per verified account in
 * {@code account_source_state} (see {@link AccountSourceStateRepository}).
 */
public final class AccountSyncStateRepository {
    public static final String BANK = "BANK";
    public static final String MATERIALS = "MATERIALS";
    public static final String RECIPES = "ACCOUNT_RECIPES";
    public static final String LUCK = "LUCK";
    /** Optional account wallet (currency balances); needs the {@code wallet} grant, which item-only workflows never do. */
    public static final String WALLET = "WALLET";
    public static final String CHARACTERS = "CHARACTERS";
    /** Optional, independently refreshed saved-equipment-tab coverage of the CHARACTERS roster. */
    public static final String CHARACTER_EQUIPMENT_TABS = "CHARACTER_EQUIPMENT_TABS";
    /** Optional, independently refreshed saved-build-tab coverage of the CHARACTERS roster (STORY-CHAR-007). */
    public static final String CHARACTER_BUILD_TABS = "CHARACTER_BUILD_TABS";
}

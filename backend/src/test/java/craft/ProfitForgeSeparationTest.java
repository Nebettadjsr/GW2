package craft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Crafting Profit is ordinary crafting only: a stale Forge opt-in is dropped at the service boundary. */
class ProfitForgeSeparationTest {

    @Test
    void staleForgeOptInIsDroppedAndEverythingElseIsPreserved() {
        CraftingSettings stale = new CraftingSettings(true, true, 2_000_000, false, false, false).withMysticForge(true);
        assertTrue(stale.allowMysticForge);
        CraftingSettings clean = CraftingSettings.ordinaryOnly(stale);
        assertFalse(clean.allowMysticForge);
        assertEquals(stale.maxBuyCopper, clean.maxBuyCopper);
        assertEquals(stale.allowBuying, clean.allowBuying);
    }

    @Test
    void nullAndAlreadyOrdinarySettingsPassThrough() {
        assertNull(CraftingSettings.ordinaryOnly(null));
        CraftingSettings plain = new CraftingSettings(true, true, 2_000_000, false, false, false);
        assertSame(plain, CraftingSettings.ordinaryOnly(plain));
    }

    @Test
    void spiritShardsHaveTheirRealName() {
        assertEquals("Spirit Shards", KnownCurrencies.name(23));
    }
}

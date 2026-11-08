package domain;

import domain.EquipmentTotals.Gear;
import domain.EquipmentTotals.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentTotalsTest {
    private static Gear item(int id, Map<String, Integer> attrs, int defense) {
        return new Gear(id, true, null, attrs, defense, List.of(), List.of(), null, true);
    }

    private static Gear rune(int id, String... bonuses) {
        return new Gear(id, true, "Rune", Map.of(), 0, List.of(), List.of(bonuses), null, true);
    }

    private static Slot slot(String name, Gear g, Gear... ups) {
        return new Slot(name, g, List.of(ups), List.of());
    }

    @Test
    void sixRunesCountEachTierOnceAndBonusesAreNotPerItem() {
        Gear r = rune(24836, "+25 Power", "+35 Ferocity", "+50 Power", "+65 Ferocity", "+100 Power", "+125 Ferocity");
        List<Slot> tab = new java.util.ArrayList<>();
        for (String s : EquipmentTotals.ARMOR_SLOTS) tab.add(slot(s, item(1, Map.of(), 10), r));
        tab.add(slot("WeaponA1", item(2, Map.of(), 0)));
        var a = EquipmentTotals.compute("Guardian", 80, tab, List.of()).a();
        assertTrue(a.complete(), a.warnings().toString());
        assertEquals(1000 + 25 + 50 + 100, a.values().get("Power"));
        assertEquals(35 + 65 + 125, a.values().get("Ferocity"));
        assertEquals(1060, a.values().get("Armor"));
        assertEquals(1645 + 10000, a.values().get("Health"));
        assertFalse(EquipmentTotals.compute("Guardian", 80, tab, List.of()).b().available());
    }

    @Test
    void partialRuneCountAndUnsupportedTextAreHonest() {
        Gear r = rune(7, "+25 Power", "When you crit, gain might");
        List<Slot> tab = List.of(slot("Helm", item(1, Map.of(), 0), r), slot("Coat", item(1, Map.of(), 0), r),
                slot("WeaponB1", item(2, Map.of(), 0)));
        var b = EquipmentTotals.compute("Thief", 80, tab, List.of()).b();
        assertFalse(b.complete());
        assertEquals(1025, b.values().get("Power"));
    }

    @Test
    void lowLevelIsUnsupportedAndMissingMetadataIsIncomplete() {
        List<Slot> tab = List.of(slot("WeaponA1", new Gear(5, false, null, null, 0, null, null, null, false)));
        var low = EquipmentTotals.compute("Thief", 79, tab, List.of()).a();
        assertTrue(low.values().isEmpty());
        assertFalse(low.complete());
        var missing = EquipmentTotals.compute("Thief", 80, tab, List.of()).a();
        assertFalse(missing.complete());
    }

    @Test
    void powerCoreDescriptionAndPercentDurationAreBounded() {
        Gear core = new Gear(96628, true, null, Map.of(), 0, List.of(), List.of(), "Increases vitality by 145.", true);
        Gear inf = new Gear(9, true, null, Map.of(), 0, List.of("+10% Boon Duration"), List.of(), null, true);
        List<Slot> tab = List.of(new Slot("WeaponA1", item(2, Map.of("Concentration", 150), 0), List.of(inf), List.of()));
        var a = EquipmentTotals.compute("Ranger", 80, tab, List.of(slot("PowerCore", core))).a();
        assertTrue(a.complete(), a.warnings().toString());
        assertEquals(1145, a.values().get("Vitality"));
        assertEquals(10 + 10, a.values().get("BoonDuration"));
        Gear odd = new Gear(96628, true, null, Map.of(), 0, List.of(), List.of(), "Increases vitality by lots", true);
        assertFalse(EquipmentTotals.compute("Ranger", 80, tab, List.of(slot("PowerCore", odd))).a().complete());
    }
}

package domain;

import domain.EquipmentTotals.Gear;
import domain.EquipmentTotals.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentTotalsTest {
    @Test
    void intrinsicItemBonusesContributeOnceAndUnknownEffectsStayIncomplete() {
        Gear back = new Gear(900, true, null, Map.of("Power", 63), 0,
                List.of("+32 Power", "+18 Precision"), List.of(), null, true);
        List<Slot> equipment = List.of(slot("Backpack", back), slot("WeaponA1", item(2, Map.of(), 0)));
        var known = EquipmentTotals.compute("Necromancer", 80, equipment, List.of()).a();
        assertTrue(known.complete(), known.warnings().toString());
        assertEquals(1095, known.values().get("Power"));
        assertEquals(1018, known.values().get("Precision"));
        Gear unsupported = new Gear(901, true, null, Map.of("Power", 63), 0,
                List.of("Additional attributes depend on an unknown effect"), List.of(), null, true);
        var partial = EquipmentTotals.compute("Necromancer", 80,
                List.of(slot("Backpack", unsupported), slot("WeaponA1", item(2, Map.of(), 0))), List.of()).a();
        assertFalse(partial.complete());
        assertEquals(1063, partial.values().get("Power"));
    }

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
        Gear core = new Gear(96628, true, null, Map.of(), 0, List.of(), List.of(), "Increases vitality by 145. Required to equip jade bot sensory arrays.", true);
        Gear inf = new Gear(9, true, null, Map.of(), 0, List.of("+10% Boon Duration"), List.of(), null, true);
        List<Slot> tab = List.of(new Slot("WeaponA1", item(2, Map.of("Concentration", 150), 0), List.of(inf), List.of()));
        var a = EquipmentTotals.compute("Ranger", 80, tab, List.of(slot("PowerCore", core))).a();
        assertTrue(a.complete(), a.warnings().toString());
        assertEquals(1145, a.values().get("Vitality"));
        assertEquals(20.0, a.values().get("BoonDuration"));
        Gear odd = new Gear(96628, true, null, Map.of(), 0, List.of(), List.of(), "Increases vitality by lots", true);
        assertFalse(EquipmentTotals.compute("Ranger", 80, tab, List.of(slot("PowerCore", odd))).a().complete());
    }

    @Test
    void fractionalPercentagesKeepTwoDecimals() {
        List<Slot> tab = List.of(new Slot("WeaponA1", item(2, Map.of("Precision", 100, "Ferocity", 400,
                "Concentration", 100, "Expertise", 359), 0), List.of(), List.of()));
        var v = EquipmentTotals.compute("Ranger", 80, tab, List.of()).a().values();
        assertEquals(9.76, v.get("CriticalChance"));
        assertEquals(176.67, v.get("CriticalDamage"));
        assertEquals(6.67, v.get("BoonDuration"));
        assertEquals(23.93, v.get("ConditionDuration"));
        assertEquals(1100, v.get("Precision"));
    }

    @Test
    void powerCoreDerivedAttributesAndMalformedNumbers() {
        String live = "Increases vitality by 145. Required to equip jade bot sensory arrays.";
        assertEquals(Map.of("Vitality", 145), EquipmentTotals.coreAttributes("PowerCore", live));
        assertNull(EquipmentTotals.coreAttributes("PowerCore", "Increases vitality by 14x. Required to equip."));
        assertNull(EquipmentTotals.coreAttributes("PowerCore", "Increases vitality by 145. Also +999 Power."));
        assertNull(EquipmentTotals.coreAttributes("Sickle", live));
    }

    @Test
    void shieldInSelectedSetAddsDefenseButInactiveShieldDoesNot() {
        Gear shield = new Gear(3, true, "Shield", Map.of("Power", 5), 120, List.of(), List.of(), null, true);
        List<Slot> tab = List.of(slot("Helm", item(1, Map.of(), 10)), slot("WeaponA1", item(2, Map.of(), 0)),
                slot("WeaponA2", shield), slot("WeaponB1", item(4, Map.of(), 0)));
        var r = EquipmentTotals.compute("Guardian", 80, tab, List.of());
        assertEquals(1000 + 10 + 120, r.a().values().get("Armor"));
        assertEquals(1010, r.b().values().get("Armor"));
    }

    @Test
    void staticSigilEffectsCountOnceAndDynamicOrUnknownAreHonest() {
        Gear conc = new Gear(72339, true, "Sigil", Map.of(), 0, List.of("+10% Boon Duration"), List.of(), null, true);
        Gear acc = new Gear(9, true, "Sigil", Map.of(), 0, List.of("+7% Critical Chance"), List.of(), null, true);
        Gear dyn = new Gear(10, true, "Sigil", Map.of(), 0, List.of("10% chance on critical to gain 5 might for 10 seconds"),
                List.of(), null, true);
        List<Slot> tab = List.of(
                new Slot("WeaponA1", item(2, Map.of(), 0), List.of(conc, acc, dyn), List.of()),
                new Slot("WeaponA2", item(3, Map.of(), 0), List.of(conc), List.of()));
        var a = EquipmentTotals.compute("Thief", 80, tab, List.of()).a();
        assertTrue(a.complete(), a.warnings().toString());
        assertEquals(10.0, a.values().get("BoonDuration"), "duplicate sigil does not stack");
        assertEquals(12.0, a.values().get("CriticalChance"));
        Gear odd = new Gear(11, true, "Sigil", Map.of(), 0, List.of("+5% Strike Damage"), List.of(), null, true);
        var w = EquipmentTotals.compute("Thief", 80,
                List.of(new Slot("WeaponA1", item(2, Map.of(), 0), List.of(odd), List.of())), List.of()).a();
        assertFalse(w.complete());
    }
}

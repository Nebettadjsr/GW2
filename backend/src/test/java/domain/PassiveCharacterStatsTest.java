package domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PassiveCharacterStatsTest {
    private static EquipmentTotals.SetResult gear() {
        return new EquipmentTotals.SetResult(true, true, Map.of(
                "Power", 2542, "Toughness", 1000, "Armor", 1967,
                "Vitality", 1181, "Health", 21022, "CriticalChance", 49.0), List.of());
    }
    private static final Map<Integer, List<Integer>> BUILD = Map.of(
            53, List.of(916, 829, 853), 50, List.of(888, 894, 893), 34, List.of(2020, 1969, 2021));

    @Test void reproducesObservedHeroPanelVitalityHealthArmorAndCriticalChanceWithConfirmedInputs() {
        var result = PassiveCharacterStats.apply(gear(), "Necromancer", BUILD, true, false, true);
        assertEquals(1494, result.values().get("Vitality"));
        assertEquals(24152, result.values().get("Health"));
        assertEquals(2015, result.values().get("Armor"));
        assertEquals(64.0, result.values().get("CriticalChance"));
        assertTrue(result.complete());
        assertEquals(1181, gear().values().get("Vitality"), "input must remain immutable");
    }
    @Test void noAnvilAssumedAndWvwUsesItsOwnRules() {
        var plain = PassiveCharacterStats.apply(gear(), "Necromancer", BUILD, true, false, false);
        assertEquals(1435, plain.values().get("Vitality"));
        assertEquals(1967, plain.values().get("Armor"));
        var wvw = PassiveCharacterStats.apply(gear(), "Necromancer", BUILD, true, true, true);
        assertEquals(1446, wvw.values().get("Vitality"));
        assertEquals(1976, wvw.values().get("Armor"));
        assertEquals(64.0, wvw.values().get("CriticalChance"));
    }
    @Test void missingOrUnmodelledBuildNeverGetsTheseBonusesByDefault() {
        var missing = PassiveCharacterStats.apply(gear(), "Necromancer", BUILD, false, false, false);
        assertEquals(1181, missing.values().get("Vitality"));
        assertEquals(49.0, missing.values().get("CriticalChance"));
        assertFalse(missing.complete());
        var other = PassiveCharacterStats.apply(gear(), "Mesmer", BUILD, true, false, false);
        assertEquals(1181, other.values().get("Vitality"));
        assertFalse(other.complete());
    }
}

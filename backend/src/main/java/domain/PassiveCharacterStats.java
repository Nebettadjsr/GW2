package domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, reviewed passive rules; never infer unconditional bonuses from arbitrary trait text.
 * PvE/WvW rules checked against /v2/traits and GW2 Wiki on 2026-11-08:
 * Spiteful Fortitude (829), Death Perception (893), Reinforced Armor.
 * Conditional damage, shroud, boons, food and kill stacks are deliberately not assumed active.
 */
public final class PassiveCharacterStats {
    private PassiveCharacterStats() {}

    // Reviewed major choices of Spite / Soul Reaping / Reaper for the initial comparison.
    // Other choices retain equipment values and explicitly flag the unmodelled trait.
    private static final Map<Integer, Set<Integer>> REVIEWED = Map.of(
            53, Set.of(916, 829, 853), 50, Set.of(888, 894, 893), 34, Set.of(2020, 1969, 2021));

    public static EquipmentTotals.SetResult apply(EquipmentTotals.SetResult gear, String profession,
            Map<Integer, List<Integer>> selections, boolean buildAvailable, boolean wvw, boolean reinforced) {
        if (!gear.available() || gear.values().isEmpty()) return gear;
        Map<String, Number> values = new LinkedHashMap<>(gear.values());
        List<String> warnings = new ArrayList<>(gear.warnings());
        boolean complete = gear.complete();
        if (!buildAvailable) { complete = false; warnings.add("Active build is missing or belongs to another account snapshot; traits excluded."); }
        boolean necromancer = "Necromancer".equals(profession);
        if (buildAvailable) {
            for (var selection : selections.entrySet()) {
                for (Integer trait : selection.getValue()) {
                    if (!necromancer || !REVIEWED.getOrDefault(selection.getKey(), Set.of()).contains(trait)) {
                        complete = false;
                        warnings.add("Passive attributes of specialization " + selection.getKey() + ", trait " + trait + " are not yet modelled.");
                    }
                }
            }
        }
        // The anvil multiplies equipment vitality/defense before attribute conversions.
        double multiplier = reinforced ? (wvw ? 1.01 : 1.05) : 1.0;
        int vitality = (int) Math.floor(values.get("Vitality").doubleValue() * multiplier);
        int toughness = values.get("Toughness").intValue();
        int defense = values.get("Armor").intValue() - toughness;
        values.put("Armor", toughness + (int) Math.floor(defense * multiplier));
        if (necromancer && buildAvailable && selections.getOrDefault(53, List.of()).contains(829)) {
            vitality += (int) Math.floor(values.get("Power").doubleValue() * 0.10);
        }
        values.put("Health", values.get("Health").intValue()
                + 10 * (vitality - values.get("Vitality").intValue()));
        values.put("Vitality", vitality);
        if (necromancer && buildAvailable && selections.getOrDefault(50, List.of()).contains(893)) {
            values.put("CriticalChance", Math.min(100.0, Math.round((values.get("CriticalChance").doubleValue()
                    + 15) * 100.0) / 100.0));
        }
        warnings.add("Saved active build; level 80 on land. Temporary effects, food, boons, shroud and kill stacks excluded.");
        return new EquipmentTotals.SetResult(true, complete, values, List.copyOf(warnings));
    }
}

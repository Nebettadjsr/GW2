package domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure BASE_PLUS_EQUIPMENT calculator (STORY-CHAR-014) for a level-80 character's land weapon sets A and B.
 * It sums the level-80 base, land armor, back/trinkets, the matching weapon pair, their upgrades and
 * infusions, rune set bonuses (once per rune item, by equipped count) and shared-slot items it can read.
 * It does not model traits, boons, food, sigil stacks or game-mode adjustments; anything it cannot read
 * makes the total incomplete instead of being guessed.
 */
public final class EquipmentTotals {
    private EquipmentTotals() {}

    public static final String SCOPE = "BASE_PLUS_EQUIPMENT";
    public static final List<String> ARMOR_SLOTS = List.of("Helm", "Shoulders", "Coat", "Gloves", "Leggings", "Boots");
    private static final List<String> FIXED_SLOTS = List.of("Backpack", "Accessory1", "Accessory2", "Ring1",
            "Ring2", "Amulet");
    private static final Set<String> PRIMARY = Set.of("Power", "Precision", "Toughness", "Vitality");
    /** Canonical attribute keys of the contract. */
    public static final List<String> KEYS = List.of("Power", "Toughness", "Armor", "Vitality", "Health", "Precision",
            "CriticalChance", "Ferocity", "CriticalDamage", "ConditionDamage", "Expertise", "ConditionDuration",
            "Concentration", "BoonDuration", "HealingPower", "AgonyResistance");

    /** One item, upgrade or infusion. {@code known} is false when its details are not stored. */
    public record Gear(int itemId, boolean known, String subtype, Map<String, Integer> attributes, int defense,
                       List<String> effects, List<String> runeBonuses, String description, boolean statsResolved) {
        public Gear {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            effects = effects == null ? List.of() : List.copyOf(effects);
            runeBonuses = runeBonuses == null ? List.of() : List.copyOf(runeBonuses);
        }
    }

    public record Slot(String slot, Gear item, List<Gear> upgrades, List<Gear> infusions) {}

    /** {@code values}: gear stats are exact integers; derived percentages are doubles rounded to 2 decimals. */
    public record SetResult(boolean available, boolean complete, Map<String, Number> values, List<String> warnings) {}

    public record Result(SetResult a, SetResult b) {}

    /** @param shared character-wide items (e.g. PowerCore) read from the equipped-slot data, may be empty */
    public static Result compute(String profession, Integer level, List<Slot> tab, List<Slot> shared) {
        return new Result(set("A", profession, level, tab, shared), set("B", profession, level, tab, shared));
    }

    /** Canonical key for an upstream attribute name, or null when unknown. */
    public static String canonical(String upstream) {
        if (upstream == null) return null;
        return switch (upstream) {
            case "Power", "Precision", "Toughness", "Vitality", "ConditionDamage", "AgonyResistance" -> upstream;
            case "Healing", "HealingPower" -> "HealingPower";
            case "CritDamage", "Ferocity" -> "Ferocity";
            case "ConditionDuration", "Expertise" -> "Expertise";
            case "BoonDuration", "Concentration" -> "Concentration";
            default -> null;
        };
    }

    private static final Pattern FLAT = Pattern.compile(
            "^\\+(\\d{1,5}) (Power|Precision|Toughness|Vitality|Ferocity|Condition Damage|Expertise|Concentration|"
                    + "Healing Power|Agony Resistance)\\.?$");
    private static final Pattern PERCENT = Pattern.compile(
            "^\\+(\\d{1,3})% (Boon Duration|Condition Duration|Critical Chance)\\.?$");
    /** First sentence carries the number; the only other sentence allowed is the known requirement text. */
    private static final Pattern CORE = Pattern.compile(
            "^Increases (vitality) by (\\d{1,4})\\.(?:\\s+Required to equip [A-Za-z ]{1,60}\\.)?$",
            Pattern.CASE_INSENSITIVE);
    /** Words of dynamic (on-hit, timed, stacking, conditional) effects, which are not modelled. */
    private static final Pattern DYNAMIC = Pattern.compile(
            "(?i)\\b(chance|when|on|upon|while|after|each|per|stack|stacks|seconds|gain|gains|grant|grants|"
                    + "trigger|triggers|if|against|every|cooldown)\\b");

    /** Parses one bounded, unconditional bonus line into {@code values} (percent durations under the *Duration keys). */
    static boolean parseLine(String line, Map<String, Integer> flat, Map<String, Integer> percent) {
        if (line == null) return false;
        String t = line.strip();
        Matcher m = FLAT.matcher(t);
        if (m.matches()) {
            String key = m.group(2).replace(" ", "");
            flat.merge(key, Integer.parseInt(m.group(1)), Integer::sum);
            return true;
        }
        m = PERCENT.matcher(t);
        if (m.matches()) {
            percent.merge(m.group(2).replace(" ", ""), Integer.parseInt(m.group(1)), Integer::sum);
            return true;
        }
        return false;
    }

    /** Field-specific extraction for the PowerCore description; null when it does not match exactly. */
    static Integer coreVitality(String description) {
        if (description == null) return null;
        Matcher m = CORE.matcher(description.strip());
        return m.matches() ? Integer.parseInt(m.group(2)) : null;
    }

    /** Derived attributes of a shared PowerCore from its description; null for other slots or unsupported text. */
    public static Map<String, Integer> coreAttributes(String slot, String description) {
        if (!"PowerCore".equals(slot)) return null;
        Integer v = coreVitality(description);
        return v == null ? null : Map.of("Vitality", v);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static SetResult set(String set, String profession, Integer level, List<Slot> tab, List<Slot> shared) {
        String w1 = "Weapon" + set + "1", w2 = "Weapon" + set + "2";
        boolean hasWeapon = tab.stream().anyMatch(s -> w1.equals(s.slot()) || w2.equals(s.slot()));
        if (!hasWeapon) return new SetResult(false, false, Map.of(), List.of("No weapons in set " + set));
        List<String> warnings = new ArrayList<>();
        if (level == null || level != 80) {
            warnings.add("Totals are only supported for level 80; level " + level + " is unsupported");
            return new SetResult(true, false, Map.of(), warnings);
        }
        Integer baseHealth = baseHealth(profession);
        if (baseHealth == null) {
            warnings.add("Base health of profession " + profession + " is unknown");
            return new SetResult(true, false, Map.of(), warnings);
        }
        boolean complete = true;
        Map<String, Integer> flat = new LinkedHashMap<>();
        Map<String, Integer> percent = new LinkedHashMap<>();
        for (String p : PRIMARY) flat.put(p, 1000);
        int defense = 0;
        Map<Integer, Integer> runeCounts = new LinkedHashMap<>();
        Map<Integer, Gear> runes = new LinkedHashMap<>();
        Set<Integer> sigils = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Slot> used = new ArrayList<>();
        for (Slot s : tab) {
            String n = s.slot();
            if (ARMOR_SLOTS.contains(n) || FIXED_SLOTS.contains(n) || w1.equals(n) || w2.equals(n)) {
                if (seen.add(n)) used.add(s);
            }
        }
        for (Slot s : shared) if ("PowerCore".equals(s.slot()) && seen.add("shared:PowerCore")) used.add(s);
        for (Slot s : used) {
            boolean shareSlot = shared.contains(s);
            Gear item = s.item();
            if (item == null || !item.known()) {
                complete = false;
                warnings.add("Details of the " + s.slot() + " item are not stored");
                continue;
            }
            if (shareSlot) {
                Integer v = item.attributes().isEmpty() ? coreVitality(item.description()) : null;
                if (v != null) flat.merge("Vitality", v, Integer::sum);
                else if (item.attributes().isEmpty() || !add(item.attributes(), flat)) {
                    complete = false;
                    warnings.add(s.slot() + " bonus is unsupported");
                }
            } else {
                if (!item.statsResolved()) { complete = false; warnings.add("Stats of the " + s.slot() + " item are unresolved"); }
                if (!add(item.attributes(), flat)) { complete = false; warnings.add(s.slot() + " has an unknown attribute"); }
                if (ARMOR_SLOTS.contains(s.slot()) || "Shield".equals(item.subtype())) defense += item.defense();
            }
            List<Gear> ups = new ArrayList<>(s.upgrades());
            ups.addAll(s.infusions());
            for (Gear u : ups) {
                if (u == null || !u.known()) {
                    complete = false;
                    warnings.add("Details of an upgrade in " + s.slot() + " are not stored");
                    continue;
                }
                if (!u.runeBonuses().isEmpty()) {
                    if (ARMOR_SLOTS.contains(s.slot())) {
                        runeCounts.merge(u.itemId(), 1, Integer::sum);
                        runes.putIfAbsent(u.itemId(), u);
                    }
                    continue;
                }
                if ("Sigil".equals(u.subtype())) {
                    if (!sigils.add(u.itemId())) continue; // a unique sigil never stacks with itself
                    if (!add(u.attributes(), flat)) { complete = false; warnings.add("Unknown attribute in a sigil of " + s.slot()); }
                    for (String line : u.effects()) {
                        if (parseLine(line, flat, percent) || DYNAMIC.matcher(line).find()) continue;
                        if (line.matches(".*\\d.*")) { complete = false; warnings.add("Unsupported sigil effect: " + line); }
                    }
                    continue;
                }
                if (!add(u.attributes(), flat)) { complete = false; warnings.add("Unknown attribute in an upgrade of " + s.slot()); }
                if (u.attributes().isEmpty() && !u.effects().isEmpty()) {
                    for (String line : u.effects()) {
                        if (!parseLine(line, flat, percent)) { complete = false; warnings.add("Unsupported upgrade effect: " + line); }
                    }
                }
            }
        }
        for (var e : runeCounts.entrySet()) {
            List<String> bonuses = runes.get(e.getKey()).runeBonuses();
            for (int i = 0; i < Math.min(e.getValue(), bonuses.size()); i++) {
                if (!parseLine(bonuses.get(i), flat, percent)) {
                    complete = false;
                    warnings.add("Unsupported rune bonus: " + bonuses.get(i));
                }
            }
        }
        Map<String, Number> out = new LinkedHashMap<>();
        int vit = flat.getOrDefault("Vitality", 0), prec = flat.getOrDefault("Precision", 0);
        int fer = flat.getOrDefault("Ferocity", 0), conc = flat.getOrDefault("Concentration", 0);
        int exp = flat.getOrDefault("Expertise", 0);
        out.put("Power", flat.get("Power"));
        out.put("Toughness", flat.get("Toughness"));
        out.put("Armor", flat.get("Toughness") + defense);
        out.put("Vitality", vit);
        out.put("Health", baseHealth + 10 * vit);
        out.put("Precision", prec);
        out.put("CriticalChance", round2(5 + (prec - 1000) / 21.0 + percent.getOrDefault("CriticalChance", 0)));
        out.put("Ferocity", fer);
        out.put("CriticalDamage", round2(150 + fer / 15.0));
        out.put("ConditionDamage", flat.getOrDefault("ConditionDamage", 0));
        out.put("Expertise", exp);
        out.put("ConditionDuration", round2(exp / 15.0 + percent.getOrDefault("ConditionDuration", 0)));
        out.put("Concentration", conc);
        out.put("BoonDuration", round2(conc / 15.0 + percent.getOrDefault("BoonDuration", 0)));
        out.put("HealingPower", flat.getOrDefault("HealingPower", 0));
        out.put("AgonyResistance", flat.getOrDefault("AgonyResistance", 0));
        return new SetResult(true, complete, out, warnings);
    }

    private static boolean add(Map<String, Integer> attributes, Map<String, Integer> into) {
        boolean ok = true;
        for (var e : attributes.entrySet()) {
            String k = canonical(e.getKey());
            if (k == null) ok = false; else into.merge(k, e.getValue(), Integer::sum);
        }
        return ok;
    }

    /** Level-80 base health before the 10-per-Vitality term; null for an unknown profession. */
    static Integer baseHealth(String profession) {
        if (profession == null) return null;
        return switch (profession) {
            case "Necromancer", "Warrior" -> 9212;
            case "Engineer", "Ranger", "Mesmer", "Revenant" -> 5922;
            case "Guardian", "Thief", "Elementalist" -> 1645;
            default -> null;
        };
    }
}

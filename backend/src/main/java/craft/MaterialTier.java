package craft;

/** Stable item ids for the tiered materials used by Forge recipes; untiered materials return null.
 * Source: https://wiki.guildwars2.com/wiki/Crafting_material/by_tier */
public final class MaterialTier {
    private MaterialTier() { }

    public static Integer of(int itemId) {
        return switch (itemId) {
            // Basic materials, fine materials, then advanced materials within each tier.
            case 19683, 19687, 19699, 19703, 19713, 19726, 19728, 19733, 19739, 19740,
                 24279, 24285, 24291, 24297, 24343, 24347, 24353,
                 24302, 24307, 24312, 24317, 24322, 24327, 24332, 24337 -> 2;
            case 19682, 19688, 19698, 19714, 19727, 19730, 19734, 19741, 19742,
                 24280, 24286, 24292, 24298, 24344, 24348, 24354,
                 24303, 24308, 24313, 24318, 24323, 24328, 24333, 24338 -> 3;
            case 19686, 19702, 19711, 19724, 19731, 19736, 19743, 19744,
                 24281, 24287, 24293, 24363, 24345, 24349, 24355,
                 24304, 24309, 24314, 24319, 24324, 24329, 24334, 24339 -> 4;
            case 19684, 19700, 19709, 19722, 19729, 19735, 19747, 19748,
                 24282, 24288, 24294, 24299, 24341, 24350, 24356,
                 24305, 24310, 24315, 24320, 24325, 24330, 24335, 24340 -> 5;
            case 19685, 19701, 19712, 19725, 19732, 19737, 19745, 19746,
                 24283, 24289, 24295, 24300, 24351, 24357, 24358 -> 6;
            default -> null;
        };
    }
}

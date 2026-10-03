package craft;

import java.util.Set;

public class DailyCrafts {

    // These are output item IDs; recipe IDs or recipe-sheet item IDs do not identify the gated output.
    private static final Set<Integer> DAILY_ITEMS = Set.of(
            43772, // Charged Quartz Crystal
            66913, // Clay Pot
            79795, // Dragon Hatchling Doll Adornments
            79726, // Dragon Hatchling Doll Eye
            79817, // Dragon Hatchling Doll Frame
            79790, // Dragon Hatchling Doll Hide
            46744, // Glob of Elder Spirit Residue
            79763, // Gossamer Stuffing
            66993, // Grow Lamp
            67015, // Heat Stone
            46742, // Lump of Mithrillium (recipe output used by recipe 7310)
            66917, // Plate of Meaty Plant Food
            66923, // Plate of Piquant Plant Food
            46740, // Spool of Silk Weaving Thread
            46745, // Spool of Thick Elonian Cord
            67377  // Vial of Maize Balm
    );

    public static boolean isDailyOutput(int itemId) {
        return DAILY_ITEMS.contains(itemId);
    }
}

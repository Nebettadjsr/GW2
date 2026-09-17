package craft;

import java.util.Set;

public class DailyCrafts {

    private static final Set<Integer> DAILY_ITEMS = Set.of(
            43772, // Charged Quartz Crystal
            70762, // Lump of Mithrillium
            70773, // Glob of Elder Spirit Residue
            70772, // Spool of Silk Weaving Thread
            74326, // Spool of Thick Elonian Cord
            74315, // Vial of Maize Balm
            66913, // Grow Lamp
            66916, // Heat Stone
            66917, // Clay Pot
            66923, // Meaty Plant Food
            66922, // Piquant Plant Food
            67377, // Doll Frame
            67378, // Doll Eye
            67379, // Doll Adornments
            67380, // Doll Hide
            70957  // Gossamer Stuffing
    );

    public static boolean isDailyOutput(int itemId) {
        return DAILY_ITEMS.contains(itemId);
    }
}
package model;

public record CharacterInfo(
        String name,
        String profession,
        String race,
        String gender,
        Integer level,
        String createdIso, // keep as ISO string; DB casts to timestamptz
        Long ageSeconds,   // played seconds; null = unknown/not retained (never zero)
        Long deaths        // total defeats; null = unknown/not retained (never zero)
) {
    /** Legacy-unknown form: playtime and deaths are not retained (null, not zero). */
    public CharacterInfo(String name, String profession, String race, String gender, Integer level,
                         String createdIso) {
        this(name, profession, race, gender, level, createdIso, null, null);
    }
}
package model;

public record CharacterInfo(
        String name,
        String profession,
        String race,
        String gender,
        Integer level,
        String createdIso // keep as ISO string; DB casts to timestamptz
) {}
package model;

import java.util.Optional;

/**
 * The closed set of build-reference resource types that carry an image (STORY-CHAR-009). The URL
 * segment, table and id column are fixed here so no request value can name a table or a host. Legends
 * are deliberately absent: upstream gives them no icon.
 */
public enum BuildResourceType {
    SPECIALIZATIONS("specializations", "build_specializations", "specialization_id"),
    TRAITS("traits", "build_traits", "trait_id"),
    SKILLS("skills", "build_skills", "skill_id"),
    PETS("pets", "build_pets", "pet_id");

    private final String segment;
    private final String table;
    private final String idColumn;

    BuildResourceType(String segment, String table, String idColumn) {
        this.segment = segment;
        this.table = table;
        this.idColumn = idColumn;
    }

    public String segment() { return segment; }
    public String table() { return table; }
    public String idColumn() { return idColumn; }

    public static Optional<BuildResourceType> ofSegment(String segment) {
        for (BuildResourceType t : values()) if (t.segment.equals(segment)) return Optional.of(t);
        return Optional.empty();
    }
}

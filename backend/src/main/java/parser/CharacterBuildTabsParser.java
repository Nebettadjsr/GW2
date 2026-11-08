package parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict parser for one character's {@code /v2/characters/:id/buildtabs?tabs=all} response
 * (STORY-CHAR-007). Every position the documented schema defines is kept, including null ones; anything
 * that cannot be stored without guessing is a validation failure. No sentinel or pet identity is ever
 * invented: an unselected slot is an explicit null (an unselected specialization keeps its object with a null id and three null traits), an absent Revenant/Ranger section stays absent.
 */
public final class CharacterBuildTabsParser {
    private CharacterBuildTabsParser() {}

    /** @param traits three nullable trait ids; all null when the position is unselected (null id) */
    public record Specialization(Integer id, List<Integer> traits) {}

    /** @param utilities exactly three positions, each possibly null */
    public record Skills(Integer heal, List<Integer> utilities, Integer elite) {}

    /**
     * @param legends land/aquatic legend ids (STRING, e.g. "Legend1"), two positions each, null when the
     *                build carries no legends
     * @param pets    land/aquatic Ranger pet ids, two positions each, null when the build carries no pets
     */
    public record Build(String name, String profession, List<Specialization> specializations,
                        Skills skills, Skills aquaticSkills,
                        List<String> legends, List<String> aquaticLegends,
                        List<Integer> pets, List<Integer> aquaticPets) {}

    public record Tab(int number, boolean active, Build build) {}

    /** @return tabs in in-game (ascending tab number) order, possibly empty */
    public static List<Tab> parse(JsonNode root) {
        if (root == null || !root.isArray()) throw invalid("response is not an array");
        List<Tab> tabs = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        int active = 0;
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) throw invalid("tab is not an object");
            JsonNode number = node.get("tab");
            if (!positiveInt(number)) throw invalid("tab number");
            if (!numbers.add(number.intValue())) throw invalid("duplicate tab number");
            JsonNode isActive = node.get("is_active");
            if (isActive == null || !isActive.isBoolean()) throw invalid("is_active");
            if (isActive.booleanValue()) active++;
            tabs.add(new Tab(number.intValue(), isActive.booleanValue(), build(node.get("build"))));
        }
        if (!tabs.isEmpty() && active != 1) throw invalid(active == 0 ? "no active tab" : "more than one active tab");
        tabs.sort(Comparator.comparingInt(Tab::number));
        return List.copyOf(tabs);
    }

    private static Build build(JsonNode b) {
        if (b == null || !b.isObject()) throw invalid("build");
        JsonNode specs = b.get("specializations");
        if (specs == null || !specs.isArray() || specs.size() != 3) throw invalid("specializations");
        List<Specialization> specializations = new ArrayList<>();
        for (JsonNode s : specs) specializations.add(specialization(s));
        return new Build(optionalText(b, "name", "build name"), optionalText(b, "profession", "profession"),
                List.copyOf(specializations), skills(b.get("skills"), "skills"),
                skills(b.get("aquatic_skills"), "aquatic_skills"),
                textPositions(b.get("legends"), "legends"), textPositions(b.get("aquatic_legends"), "aquatic_legends"),
                idPositions(pet(b, "terrestrial"), "terrestrial pets"),
                idPositions(pet(b, "aquatic"), "aquatic pets"));
    }

    /**
     * Pets are a Ranger-only section: absent or explicit null means none. When present both environment
     * arrays are required (two positions each, null elements are empty positions).
     */
    private static JsonNode pet(JsonNode build, String environment) {
        JsonNode pets = build.get("pets");
        if (pets == null || pets.isNull()) return null;
        if (!pets.isObject()) throw invalid("pets");
        JsonNode env = pets.get(environment);
        if (env == null || env.isNull()) throw invalid(environment + " pets missing");
        return env;
    }

    /**
     * The documented shape is always an object with an explicit {@code id} and three explicit trait
     * positions; an unselected position is {@code {"id":null,"traits":[null,null,null]}}. A bare null,
     * a missing field, or a null id with selected traits is malformed.
     */
    private static Specialization specialization(JsonNode s) {
        if (s == null || !s.isObject()) throw invalid("specialization");
        JsonNode id = s.get("id");
        if (id == null) throw invalid("specialization id missing");
        JsonNode traits = s.get("traits");
        if (traits == null || !traits.isArray() || traits.size() != 3) throw invalid("specialization traits");
        List<Integer> out = new ArrayList<>();
        for (JsonNode t : traits) out.add(nullableId(t, "trait"));
        if (id.isNull()) {
            if (out.stream().anyMatch(java.util.Objects::nonNull)) throw invalid("traits selected without specialization");
            return new Specialization(null, java.util.Collections.unmodifiableList(out));
        }
        if (!positiveInt(id)) throw invalid("specialization");
        return new Specialization(id.intValue(), java.util.Collections.unmodifiableList(out));
    }

    private static Skills skills(JsonNode s, String what) {
        if (s == null || !s.isObject()) throw invalid(what);
        JsonNode utilities = s.get("utilities");
        if (utilities == null || !utilities.isArray() || utilities.size() != 3) throw invalid(what + " utilities");
        List<Integer> out = new ArrayList<>();
        for (JsonNode u : utilities) out.add(nullableId(u, what + " utility"));
        return new Skills(requiredNullableId(s, "heal", what), java.util.Collections.unmodifiableList(out),
                nullableId(s.get("elite"), what + " elite"));
    }

    /** Two ordered positions, each a non-blank string or null; null/absent array means none. */
    private static List<String> textPositions(JsonNode n, String what) {
        if (n == null || n.isNull()) return null;
        if (!n.isArray() || n.size() != 2) throw invalid(what);
        String[] out = new String[2];
        int i = 0;
        for (JsonNode e : n) {
            if (!e.isNull()) {
                if (!e.isTextual() || e.asText().isBlank()) throw invalid(what);
                out[i] = e.asText();
            }
            i++;
        }
        return Arrays.asList(out);
    }

    /** Two ordered pet positions: a positive id or null. Zero and other sentinels are rejected. */
    private static List<Integer> idPositions(JsonNode n, String what) {
        if (n == null || n.isNull()) return null;
        if (!n.isArray() || n.size() != 2) throw invalid(what);
        Integer[] out = new Integer[2];
        int i = 0;
        for (JsonNode e : n) out[i++] = nullableId(e, what);
        return Arrays.asList(out);
    }

    /** A documented slot: the field must be present; an explicit null means nothing chosen. */
    private static Integer requiredNullableId(JsonNode parent, String field, String what) {
        if (!parent.has(field)) throw invalid(what + " " + field + " missing");
        return nullableId(parent.get(field), what + " " + field);
    }

    private static Integer nullableId(JsonNode n, String what) {
        if (n == null || n.isNull()) return null;
        if (!positiveInt(n)) throw invalid(what);
        return n.intValue();
    }

    private static String optionalText(JsonNode parent, String field, String what) {
        JsonNode v = parent.get(field);
        if (v == null) throw invalid(what + " missing");
        if (v.isNull()) return null;
        if (!v.isTextual()) throw invalid(what);
        return v.asText();
    }

    private static boolean positiveInt(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.intValue() > 0;
    }

    private static IllegalStateException invalid(String what) {
        return new IllegalStateException("Malformed build tabs response: " + what);
    }
}

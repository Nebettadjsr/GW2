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
 * invented: an unselected slot is {@code null}, an absent Revenant/Ranger section stays absent.
 */
public final class CharacterBuildTabsParser {
    private CharacterBuildTabsParser() {}

    /** @param traits three nullable trait ids; null (with a null id) when the position is unselected */
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
                idPositions(b.get("pets") == null ? null : pet(b, "terrestrial"), "terrestrial pets"),
                idPositions(b.get("pets") == null ? null : pet(b, "aquatic"), "aquatic pets"));
    }

    private static JsonNode pet(JsonNode build, String environment) {
        JsonNode pets = build.get("pets");
        if (pets.isNull()) return null;
        if (!pets.isObject()) throw invalid("pets");
        return pets.get(environment);
    }

    private static Specialization specialization(JsonNode s) {
        if (s == null || s.isNull()) return new Specialization(null, null);
        if (!s.isObject() || !positiveInt(s.get("id"))) throw invalid("specialization");
        JsonNode traits = s.get("traits");
        if (traits == null || !traits.isArray() || traits.size() != 3) throw invalid("specialization traits");
        List<Integer> out = new ArrayList<>();
        for (JsonNode t : traits) out.add(nullableId(t, "trait"));
        return new Specialization(s.get("id").intValue(), java.util.Collections.unmodifiableList(out));
    }

    private static Skills skills(JsonNode s, String what) {
        if (s == null || !s.isObject()) throw invalid(what);
        JsonNode utilities = s.get("utilities");
        if (utilities == null || !utilities.isArray() || utilities.size() != 3) throw invalid(what + " utilities");
        List<Integer> out = new ArrayList<>();
        for (JsonNode u : utilities) out.add(nullableId(u, what + " utility"));
        return new Skills(nullableId(s.get("heal"), what + " heal"), java.util.Collections.unmodifiableList(out),
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

    private static Integer nullableId(JsonNode n, String what) {
        if (n == null || n.isNull()) return null;
        if (!positiveInt(n)) throw invalid(what);
        return n.intValue();
    }

    private static String optionalText(JsonNode parent, String field, String what) {
        JsonNode v = parent.get(field);
        if (v == null || v.isNull()) return null;
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

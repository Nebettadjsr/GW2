package repo;

public class DiscChoice {
    public enum Kind { ALL, DISCIPLINE_ONLY, CHAR_DISCIPLINE }

    public final Kind kind;
    public final String discipline;   // for DISCIPLINE_ONLY / CHAR_DISCIPLINE
    public final String charName;     // only for CHAR_DISCIPLINE
    public final int rating;          // only for CHAR_DISCIPLINE

    private DiscChoice(Kind kind, String discipline, String charName, int rating) {
        this.kind = kind;
        this.discipline = discipline;
        this.charName = charName;
        this.rating = rating;
    }

    public static DiscChoice all() { return new DiscChoice(Kind.ALL, null, null, 0); }
    public static DiscChoice disciplineOnly(String d) { return new DiscChoice(Kind.DISCIPLINE_ONLY, d, null, 0); }
    public static DiscChoice charDiscipline(String d, int rating, String charName) {
        return new DiscChoice(Kind.CHAR_DISCIPLINE, d, charName, rating);
    }

    @Override public String toString() {
        return switch (kind) {
            case ALL -> "All";
            case DISCIPLINE_ONLY -> discipline;
            case CHAR_DISCIPLINE -> discipline + " lvl " + rating + " — " + charName;
        };
    }
}
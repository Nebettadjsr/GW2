package craft;

/** Independent crafting-domain recipe ingredient (STORY-DOM-017): no persistence/JDBC dependency. */
public class Ingredient {
    public final int itemId;
    public final int count;

    public Ingredient(int itemId, int count) {
        this.itemId = itemId;
        this.count = count;
    }
}

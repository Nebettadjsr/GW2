package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.Ingredient;
import craft.MysticForgeCatalog;
import craft.Recipe;
import craft.RecipeSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Reads the bundled normalized Mystic Forge snapshot into the domain {@link MysticForgeCatalog}. */
public final class MysticForgeCatalogLoader {

    public static final String RESOURCE = "/mystic-forge/catalog.json";

    private static final MysticForgeCatalog BUNDLED = load(RESOURCE);

    private MysticForgeCatalogLoader() { }

    public static MysticForgeCatalog bundled() { return BUNDLED; }

    static MysticForgeCatalog load(String resource) {
        try (InputStream in = MysticForgeCatalogLoader.class.getResourceAsStream(resource)) {
            if (in == null) return new MysticForgeCatalog(List.of(), 0, "missing");
            return fromJson(new ObjectMapper().readTree(in));
        } catch (IOException | RuntimeException e) {
            // An unreadable snapshot supplies no Forge recipes; it never invents any.
            return new MysticForgeCatalog(List.of(), 0, "unreadable");
        }
    }

    /** Normalized form: {source, excludedCount, recipes:[{forgeId, outputItemId, outputCount, ingredients:[[id,count]]}]}. */
    public static MysticForgeCatalog fromJson(JsonNode root) {
        List<Recipe> out = new ArrayList<>();
        for (JsonNode r : root.path("recipes")) {
            int forgeId = r.path("forgeId").asInt(0), output = r.path("outputItemId").asInt(0),
                    qty = r.path("outputCount").asInt(0);
            List<Ingredient> ingredients = new ArrayList<>();
            for (JsonNode i : r.path("ingredients")) {
                ingredients.add(new Ingredient(i.path(0).asInt(0), i.path(1).asInt(0)));
            }
            boolean valid = forgeId > 0 && output > 0 && qty > 0 && !ingredients.isEmpty()
                    && ingredients.stream().allMatch(i -> i.itemId > 0 && i.count > 0);
            if (valid) {
                out.add(new Recipe(MysticForgeCatalogParser.recipeIdFor(forgeId), output, qty, 0, "", ingredients,
                        List.of(), RecipeSource.MYSTIC_FORGE));
            }
        }
        return new MysticForgeCatalog(out, root.path("excludedCount").asInt(0), root.path("source").asText(""));
    }
}

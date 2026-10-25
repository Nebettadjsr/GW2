package craft;

import java.util.Map;

/**
 * One synced character's crafting-rating data, as needed by coordinated multi-character
 * planning (DOMAIN_SPEC.md section 2.2.1 / STORY-DOM-014): the character's name plus their
 * rating in each crafting discipline they have trained. A character absent from a discipline's
 * key set has never trained it and is therefore never eligible for a recipe requiring it.
 */
public record CharacterCraftingProfile(String name, Map<String, Integer> ratingByDiscipline) {
}

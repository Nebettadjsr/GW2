package web;

import application.CraftingResolutionDetail;
import application.icons.ItemIconUrls;
import craft.AcquisitionMethod;
import craft.BlockedReason;
import craft.CraftTraceNode;
import craft.ResolutionState;
import repo.ItemRepository;
import web.dto.CraftingRowDto;
import web.dto.ResolutionNodeDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The part of the resolution-detail contract both routes share (STORY-API-008,
 * TARGET_ARCHITECTURE.md §10.2 and STORY-API-008): the required-input rules, the envelope literals,
 * the row
 * projection and the recursive tree copy. What differs between Profit and Discovery - their scope,
 * settings, defaults and validation - stays in {@link CraftingProfitApiMapper} and
 * {@link CraftingDiscoveryApiMapper}, which this class does not duplicate.
 *
 * <p>It only validates and copies. It runs no resolution, selects no recipe, derives no cost or
 * quantity, and infers no domain state from a price: every value it emits was produced by the
 * authoritative resolver and handed over by {@code application.CraftingResolutionDetail}. Nothing
 * here reads the database or the crafting graph, so mapping cannot introduce a second data read into
 * an operation (§13.2). It is stateless, so it is safe to share across concurrent requests.
 */
final class CraftingResolutionMapper {

    /**
     * §13.3: this response is a fresh calculation, not retrieval of the table's historical result.
     * There is no cross-request snapshot guarantee behind it.
     */
    static final String CONSISTENCY_FRESH_CALCULATION = "FRESH_CALCULATION";

    /** Profit tree root quantity when the selected row has counted output. */
    static final String TREE_BASIS_SELECTED_RESULT_OUTPUT_QUANTITY = "SELECTED_RESULT_OUTPUT_QUANTITY";

    /** Zero-count rows retain one rejected attempt so its item-specific blocker remains visible. */
    static final String TREE_BASIS_FIRST_BLOCKED_ATTEMPT = "FIRST_BLOCKED_ATTEMPT";

    /** Discovery still explains one output batch because it has no selected Profit craft count. */
    static final String TREE_BASIS_SINGLE_OUTPUT_REQUIREMENT = "SINGLE_OUTPUT_REQUIREMENT";

    static final String TREE_STATUS_AVAILABLE = "AVAILABLE";
    static final String TREE_STATUS_RESULT_UNAVAILABLE = "RESULT_UNAVAILABLE";

    private CraftingResolutionMapper() {}

    /**
     * The body itself: required on both detail routes, unlike the Profit table route's optional one -
     * there is no default recipe to explain (§13.1).
     *
     * @throws ApiValidationException if it is absent
     */
    static <T> T requireBody(T request) {
        if (request == null) {
            throw new ApiValidationException(
                    "a request body is required: it must carry recipeId and calculation");
        }
        return request;
    }

    /**
     * The requested recipe identity: required, and positive because a recipe ID is. Validated before
     * any service is created, so an unusable request starts no calculation.
     *
     * @throws ApiValidationException if it is absent or not positive
     */
    static int requireRecipeId(Integer recipeId) {
        if (recipeId == null) {
            throw new ApiValidationException(
                    "recipeId is required: resolution detail explains one explicitly selected recipe");
        }
        if (recipeId <= 0) {
            throw new ApiValidationException("recipeId must be a positive integer");
        }
        return recipeId;
    }

    /**
     * The nested calculation: required on both detail routes, because the caller is expected to send
     * the effective inputs the table response echoed rather than rely on defaults a second time
     * (§13.1). Its individual members keep each table contract's own optional-with-defaults
     * semantics, which the route's existing mapper applies.
     *
     * @throws ApiValidationException if it is absent
     */
    static <T> T requireCalculation(T calculation) {
        if (calculation == null) {
            throw new ApiValidationException(
                    "calculation is required: it carries the scope and settings the recipe is resolved in");
        }
        return calculation;
    }

    /**
     * The one outcome of §13.4 that is not a completed 200: the recipe is valid but this operation's
     * own fresh candidate set does not contain it.
     *
     * @throws RecipeNotInCalculationException mapped to 404 {@code RECIPE_NOT_IN_CALCULATION}
     */
    static CraftingResolutionDetail requireInCalculation(CraftingResolutionDetail detail) {
        if (detail.status() == CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION) {
            throw new RecipeNotInCalculationException(detail.recipeId());
        }
        return detail;
    }

    /** UTC ISO-8601 completion instant; informational, not a data version or snapshot identifier. */
    static String calculatedAt() {
        return Instant.now().toString();
    }

    /**
     * {@code AVAILABLE} for an explanation that exists - including a blocked one, which carries its
     * reasons - and {@code RESULT_UNAVAILABLE} when there is no resolution result to explain.
     */
    static String treeStatus(CraftingResolutionDetail detail) {
        return detail.status() == CraftingResolutionDetail.Status.RESULT_UNAVAILABLE
                ? TREE_STATUS_RESULT_UNAVAILABLE
                : TREE_STATUS_AVAILABLE;
    }

    static String profitTreeBasis(CraftingResolutionDetail detail) {
        return detail.status() == CraftingResolutionDetail.Status.AVAILABLE
                && detail.row() != null && detail.row().craftableCount == 0
                ? TREE_BASIS_FIRST_BLOCKED_ATTEMPT
                : TREE_BASIS_SELECTED_RESULT_OUTPUT_QUANTITY;
    }

    /**
     * This operation's row for the selected recipe, projected by the same {@link CraftingRowMapper}
     * the table routes use, from the recipe, quotes and item metadata <em>this</em> operation
     * captured.
     */
    static CraftingRowDto toRow(CraftingResolutionDetail detail) {
        return CraftingRowMapper.toRow(detail.recipe(), detail.row(), detail.items(), detail.quotes());
    }

    /**
     * The resolution tree, or null when there is no result to explain - never a fabricated empty
     * tree. Item names and icon sources come only from the metadata this operation captured, and each
     * node carries its <em>own</em> item's icon: a requirement satisfied from stock, bought or crafted
     * is still that item (TARGET_ARCHITECTURE.md §10.2), so nothing here reads the node's sourcing
     * or the requested recipe to decide an image.
     */
    static ResolutionNodeDto toTree(CraftingResolutionDetail detail) {
        if (detail.explanation() == null || detail.explanation().root() == null) return null;
        return toNode(detail.explanation().root(), detail.itemNames(), detail.items(), Set.of(), Set.of());
    }

    /** Discovery-only projection: adds knowledge for recipes proven known or eligible to discover. */
    static ResolutionNodeDto toDiscoveryTree(CraftingResolutionDetail detail) {
        if (detail.explanation() == null || detail.explanation().root() == null) return null;
        return toNode(detail.explanation().root(), detail.itemNames(), detail.items(),
                detail.knownUsableRecipeIds(), detail.discoverableRecipeIds());
    }

    private static ResolutionNodeDto toNode(CraftTraceNode node,
                                            Map<Integer, String> itemNames,
                                            Map<Integer, ItemRepository.ItemInfo> items,
                                            Set<Integer> knownRecipeIds,
                                            Set<Integer> discoverableRecipeIds) {
        List<ResolutionNodeDto> children = new ArrayList<>(node.children().size());
        for (CraftTraceNode child : node.children()) {
            children.add(toNode(child, itemNames, items, knownRecipeIds, discoverableRecipeIds));
        }

        return new ResolutionNodeDto(
                node.itemId(),
                itemNames.get(node.itemId()),
                node.requestedQuantity(),
                node.inventoryQuantity(),
                node.craftedQuantity(),
                node.boughtQuantity(),
                node.missingQuantity(),
                node.recipeId(),
                node.craftCount(),
                node.producedQuantity(),
                node.characterName(),
                methodNames(node.methods()),
                stateNames(node.states()),
                reasonNames(node.blockedReasons()),
                node.cashCostCopper(),
                node.opportunityCostCopper(),
                node.effectiveCostCopper(),
                children,
                iconUrl(node.itemId(), items),
                recipeKnowledge(node.recipeId(), knownRecipeIds, discoverableRecipeIds));
    }

    private static String recipeKnowledge(Integer recipeId,
                                          Set<Integer> knownRecipeIds,
                                          Set<Integer> discoverableRecipeIds) {
        if (recipeId == null) return null;
        if (knownRecipeIds.contains(recipeId)) return "KNOWN";
        if (discoverableRecipeIds.contains(recipeId)) return "TO_DISCOVER";
        return null;
    }

    /** Null when this operation captured no item metadata for the node, or none that is acceptable. */
    private static String iconUrl(int itemId, Map<Integer, ItemRepository.ItemInfo> items) {
        ItemRepository.ItemInfo info = items.get(itemId);
        return info == null ? null : ItemIconUrls.iconUrlFor(itemId, info.iconUrl);
    }

    private static List<String> methodNames(List<AcquisitionMethod> methods) {
        List<String> names = new ArrayList<>(methods.size());
        for (AcquisitionMethod method : methods) names.add(method.name());
        return names;
    }

    private static List<String> stateNames(List<ResolutionState> states) {
        List<String> names = new ArrayList<>(states.size());
        for (ResolutionState state : states) names.add(state.name());
        return names;
    }

    private static List<String> reasonNames(List<BlockedReason> reasons) {
        List<String> names = new ArrayList<>(reasons.size());
        for (BlockedReason reason : reasons) names.add(reason.name());
        return names;
    }
}

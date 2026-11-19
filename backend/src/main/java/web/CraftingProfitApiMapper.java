package web;

import application.CalculationFreshness;
import application.CraftingProfitService;
import craft.CraftingSettings;
import repo.DiscChoice;
import web.dto.CraftingProfitRequest;
import web.dto.CraftingProfitResponse;
import web.dto.CraftingRowDto;

import java.util.List;
import java.util.Map;

/**
 * Translation between the HTTP transport DTOs and the existing application/domain inputs and
 * results; business rules do not belong in the HTTP layer.
 *
 * <p>This class only defaults, validates and copies. It performs no crafting calculation, derives
 * no total, and applies no display rule - every number it emits is the one the domain produced.
 * It is stateless, so it is safe to share across concurrent requests.
 */
final class CraftingProfitApiMapper {

    /**
     * Defaults for omitted request fields: the values an empty request body calculates with
     * (All scope, own materials on, buying off, 1g budget, instant sell/buy, daily crafts off).
     */
    static final String DEFAULT_SCOPE_KIND = "ALL";
    static final boolean DEFAULT_USE_OWN_MATS = true;
    static final boolean DEFAULT_ALLOW_BUYING = false;
    static final int DEFAULT_MAX_BUY_COPPER = 10_000; // 1g budget
    static final boolean DEFAULT_LISTING_SELL = false; // instant sell
    static final boolean DEFAULT_LISTING_BUY = false;  // instant buy
    // Preserve the former Profit default: daily outputs were not crafted automatically.
    static final boolean DEFAULT_ALLOW_DAILY_CRAFTS = false;

    private static final String SCOPE_ALL = "ALL";
    private static final String SCOPE_DISCIPLINE = "DISCIPLINE";
    private static final String SCOPE_CHARACTER_DISCIPLINE = "CHARACTER_DISCIPLINE";

    private CraftingProfitApiMapper() {}

    /**
     * Applies the documented defaults to {@code request} (which may itself be null) and validates
     * the outcome.
     *
     * @throws ApiValidationException if the completed request cannot describe a calculation
     */
    static Effective toEffective(CraftingProfitRequest request) {
        CraftingProfitRequest.ScopeDto scope = request == null ? null : request.scope();
        CraftingProfitRequest.SettingsDto settings = request == null ? null : request.settings();

        return new Effective(toEffectiveScope(scope), toEffectiveSettings(settings));
    }

    private static CraftingProfitResponse.EffectiveScopeDto toEffectiveScope(
            CraftingProfitRequest.ScopeDto scope) {

        String kind = scope == null || scope.kind() == null ? DEFAULT_SCOPE_KIND : scope.kind();
        String discipline = scope == null ? null : trimToNull(scope.discipline());
        String characterName = scope == null ? null : trimToNull(scope.characterName());
        int rating = scope == null || scope.rating() == null ? 0 : scope.rating();

        switch (kind) {
            case SCOPE_ALL -> {
                return new CraftingProfitResponse.EffectiveScopeDto(SCOPE_ALL, null, null, 0);
            }
            case SCOPE_DISCIPLINE -> {
                requirePresent(discipline, "scope.discipline", SCOPE_DISCIPLINE);
                return new CraftingProfitResponse.EffectiveScopeDto(SCOPE_DISCIPLINE, discipline, null, 0);
            }
            case SCOPE_CHARACTER_DISCIPLINE -> {
                requirePresent(discipline, "scope.discipline", SCOPE_CHARACTER_DISCIPLINE);
                requirePresent(characterName, "scope.characterName", SCOPE_CHARACTER_DISCIPLINE);
                if (rating < 0) {
                    throw new ApiValidationException("scope.rating must not be negative");
                }
                return new CraftingProfitResponse.EffectiveScopeDto(
                        SCOPE_CHARACTER_DISCIPLINE, discipline, characterName, rating);
            }
            default -> throw new ApiValidationException(
                    "scope.kind must be one of " + SCOPE_ALL + ", " + SCOPE_DISCIPLINE + ", "
                            + SCOPE_CHARACTER_DISCIPLINE);
        }
    }

    private static CraftingProfitResponse.EffectiveSettingsDto toEffectiveSettings(
            CraftingProfitRequest.SettingsDto settings) {

        int maxBuyCopper = settings == null || settings.maxBuyCopper() == null
                ? DEFAULT_MAX_BUY_COPPER
                : settings.maxBuyCopper();
        if (maxBuyCopper < 0) {
            throw new ApiValidationException("settings.maxBuyCopper must not be negative");
        }

        List<Integer> globalAll = canonicalProtectedIds(settings == null ? null : settings.protectedItemIds(), "protectedItemIds");
        List<Integer> bankAll = canonicalProtectedIds(settings == null ? null : settings.protectedBankItemIds(), "protectedBankItemIds");
        List<Integer> materialAll = canonicalProtectedIds(settings == null ? null : settings.protectedMaterialItemIds(), "protectedMaterialItemIds");
        Map<Integer, Integer> bankQty = withoutAll(canonicalQuantities(settings == null ? null : settings.protectedBankQuantities(), "protectedBankQuantities"), bankAll, globalAll);
        Map<Integer, Integer> materialQty = withoutAll(canonicalQuantities(settings == null ? null : settings.protectedMaterialQuantities(), "protectedMaterialQuantities"), materialAll, globalAll);
        List<craft.BankAllocation> rawAllocations = canonicalAllocations(
                settings == null ? null : settings.protectedBankAllocations());
        List<craft.BankAllocation> bankAllocations = validatedAllocations(rawAllocations, bankQty, bankAll, globalAll);
        return new CraftingProfitResponse.EffectiveSettingsDto(
                orDefault(settings == null ? null : settings.useOwnMats(), DEFAULT_USE_OWN_MATS),
                orDefault(settings == null ? null : settings.allowBuying(), DEFAULT_ALLOW_BUYING),
                maxBuyCopper,
                orDefault(settings == null ? null : settings.listingSell(), DEFAULT_LISTING_SELL),
                orDefault(settings == null ? null : settings.listingBuy(), DEFAULT_LISTING_BUY),
                orDefault(settings == null ? null : settings.allowDailyCrafts(),
                        DEFAULT_ALLOW_DAILY_CRAFTS),
                orDefault(settings == null ? null : settings.materialProtectionEnabled(), false),
                globalAll, bankAll, materialAll, bankQty, materialQty, bankAllocations,
                orDefault(settings == null ? null : settings.allowMysticForge(), false));
    }

    /** Entry shape and ranges only; sorted by slot. Duplicate slots are rejected. */
    private static List<craft.BankAllocation> canonicalAllocations(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new ApiValidationException("settings.protectedBankAllocations must be an array");
        }
        java.util.ArrayList<craft.BankAllocation> out = new java.util.ArrayList<>();
        java.util.HashSet<Integer> slots = new java.util.HashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> m)) {
                throw new ApiValidationException("settings.protectedBankAllocations entries must be objects");
            }
            int slot = wholeInt(m, "slot", 0);
            int itemId = wholeInt(m, "itemId", 1);
            int observed = wholeInt(m, "observedCount", 1);
            int reserved = wholeInt(m, "reservedUnits", 0);
            if (reserved > observed) {
                throw new ApiValidationException("settings.protectedBankAllocations reservedUnits must not exceed observedCount");
            }
            String binding = nullableString(m, "binding");
            String boundTo = nullableString(m, "boundTo");
            if (!slots.add(slot)) {
                throw new ApiValidationException("settings.protectedBankAllocations slots must be unique");
            }
            out.add(new craft.BankAllocation(slot, itemId, reserved, observed, binding, boundTo));
        }
        out.sort(java.util.Comparator.comparingInt(craft.BankAllocation::slot));
        return java.util.Collections.unmodifiableList(out);
    }

    private static int wholeInt(Map<?, ?> m, String key, int min) {
        Object v = m.get(key);
        long n;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            n = ((Number) v).longValue();
        } else {
            throw new ApiValidationException("settings.protectedBankAllocations " + key + " must be a whole number");
        }
        if (n < min || n > Integer.MAX_VALUE) {
            throw new ApiValidationException("settings.protectedBankAllocations " + key + " is out of range");
        }
        return (int) n;
    }

    private static String nullableString(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof String str) return str;
        throw new ApiValidationException("settings.protectedBankAllocations " + key + " must be a string or null");
    }

    /** Applies dominant ALL, then checks each remaining group against its fixed target. */
    private static List<craft.BankAllocation> validatedAllocations(List<craft.BankAllocation> all,
            Map<Integer, Integer> target, List<Integer> sourceAll, List<Integer> globalAll) {
        java.util.ArrayList<craft.BankAllocation> kept = new java.util.ArrayList<>();
        Map<Integer, long[]> sums = new java.util.HashMap<>();
        for (craft.BankAllocation a : all) {
            if (sourceAll.contains(a.itemId()) || globalAll.contains(a.itemId())) continue;
            kept.add(a);
            long[] s = sums.computeIfAbsent(a.itemId(), k -> new long[2]);
            s[0] += a.reservedUnits();
            s[1] += a.observedCount();
        }
        for (Map.Entry<Integer, long[]> e : sums.entrySet()) {
            Integer t = target.get(e.getKey());
            if (t == null) {
                throw new ApiValidationException("settings.protectedBankAllocations item has no protectedBankQuantities target");
            }
            if (e.getValue()[0] != Math.min((long) t, e.getValue()[1])) {
                throw new ApiValidationException("settings.protectedBankAllocations reservedUnits must sum to the item's reserved quantity");
            }
        }
        return java.util.Collections.unmodifiableList(kept);
    }

    /** Reserve-ALL for an item takes precedence over its finite target. */
    private static Map<Integer, Integer> withoutAll(Map<Integer, Integer> qty, List<Integer> sourceAll, List<Integer> globalAll) {
        java.util.TreeMap<Integer, Integer> out = new java.util.TreeMap<>(qty);
        out.keySet().removeIf(id -> sourceAll.contains(id) || globalAll.contains(id));
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Finite reservation targets: keys are positive item IDs, values nonnegative whole numbers within
     * int range. Zero clears. Fractions, negatives and non-numbers are rejected, never truncated.
     */
    private static Map<Integer, Integer> canonicalQuantities(Map<?, ?> raw, String field) {
        if (raw == null) return Map.of();
        java.util.TreeMap<Integer, Integer> out = new java.util.TreeMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            int id;
            try {
                id = Integer.parseInt(String.valueOf(e.getKey()));
            } catch (NumberFormatException ex) {
                throw new ApiValidationException("settings." + field + " keys must be positive item IDs");
            }
            if (id <= 0) throw new ApiValidationException("settings." + field + " keys must be positive item IDs");
            Object v = e.getValue();
            long qty;
            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                qty = ((Number) v).longValue();
            } else if (v instanceof java.math.BigInteger big && big.bitLength() < 63) {
                qty = big.longValue();
            } else {
                throw new ApiValidationException("settings." + field + " values must be whole numbers");
            }
            if (qty < 0 || qty > Integer.MAX_VALUE) {
                throw new ApiValidationException("settings." + field + " values must be between 0 and " + Integer.MAX_VALUE);
            }
            if (qty > 0) out.put(id, (int) qty);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Positive IDs only; duplicates removed; ascending numeric order. No inventory/item reads. */
    private static List<Integer> canonicalProtectedIds(List<Integer> ids, String field) {
        if (ids == null) return List.of();
        java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>();
        for (Integer id : ids) {
            if (id == null || id <= 0) {
                throw new ApiValidationException(
                        "settings." + field + " must contain only positive item IDs");
            }
            sorted.add(id);
        }
        return List.copyOf(sorted);
    }

    /** A validated request, in both its transport form (echoed back) and its application form. */
    record Effective(CraftingProfitResponse.EffectiveScopeDto scope,
                     CraftingProfitResponse.EffectiveSettingsDto settings) {

        DiscChoice toDiscChoice() {
            return switch (scope.kind()) {
                case SCOPE_DISCIPLINE -> DiscChoice.disciplineOnly(scope.discipline());
                case SCOPE_CHARACTER_DISCIPLINE ->
                        DiscChoice.charDiscipline(scope.discipline(), scope.rating(), scope.characterName());
                default -> DiscChoice.all();
            };
        }

        CraftingSettings toCraftingSettings() {
            return new CraftingSettings(
                    settings.useOwnMats(),
                    settings.allowBuying(),
                    settings.maxBuyCopper(),
                    settings.listingSell(),
                    settings.listingBuy(),
                    settings.allowDailyCrafts(),
                    settings.materialProtectionEnabled(),
                    settings.protectedItemIds(), settings.protectedBankItemIds(), settings.protectedMaterialItemIds(),
                    settings.protectedBankQuantities(), settings.protectedMaterialQuantities(),
                    settings.protectedBankAllocations(), settings.allowMysticForge());
        }
    }

    /**
     * Maps one {@link CraftingProfitService.ProfitData} into the transport response, in the
     * repository's visible-recipe order. Row copying itself lives in {@link CraftingRowMapper},
     * shared with Crafting Discovery.
     */
    static CraftingProfitResponse toResponse(Effective effective,
                                             CraftingProfitService.ProfitData data,
                                             CalculationFreshness freshness) {
        List<CraftingRowDto> rows = CraftingRowMapper.toRows(
                data.visibleRecipes(), data.resultsByRecipeId(), data.items(), data.tp());

        return new CraftingProfitResponse(CraftingProfitResponse.STATUS_CALCULATED,
                effective.scope(), effective.settings(), rows.size(), rows,
                CalculationFreshnessMapper.toDto(freshness));
    }

    /**
     * The no-usable-cache response: the effective inputs that were asked for and the freshness that
     * explains why nothing could be calculated, with no rows at all. An empty row list is not used,
     * because it would be indistinguishable from "this scope has no craftable recipes".
     */
    static CraftingProfitResponse toPendingResponse(Effective effective, CalculationFreshness freshness) {
        return new CraftingProfitResponse(CraftingProfitResponse.STATUS_PENDING,
                effective.scope(), effective.settings(), null, null,
                CalculationFreshnessMapper.toDto(freshness));
    }

    private static void requirePresent(String value, String field, String kind) {
        if (value == null) {
            throw new ApiValidationException(field + " is required for scope.kind " + kind);
        }
    }

    private static boolean orDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package model;

public record CharacterItemRow(
        String location,       // "BAG" or "EQUIPMENT"
        Integer bagIndex,      // BAG only
        Integer slotIndex,     // BAG only
        String equipmentSlot,  // EQUIPMENT only
        int itemId,
        Integer count,
        String binding,
        String boundTo,
        java.util.List<Integer> upgradeIds
) {
    public CharacterItemRow {
        upgradeIds = upgradeIds == null ? java.util.List.of() : java.util.List.copyOf(upgradeIds);
    }
    public CharacterItemRow(String location, Integer bagIndex, Integer slotIndex, String equipmentSlot,
                            int itemId, Integer count, String binding, String boundTo) {
        this(location, bagIndex, slotIndex, equipmentSlot, itemId, count, binding, boundTo, java.util.List.of());
    }
}

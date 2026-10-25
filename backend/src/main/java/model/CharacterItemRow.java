package model;

public record CharacterItemRow(
        String location,       // "BAG" or "EQUIPMENT"
        Integer bagIndex,      // BAG only
        Integer slotIndex,     // BAG only
        String equipmentSlot,  // EQUIPMENT only
        int itemId,
        Integer count,
        String binding,
        String boundTo
) {}

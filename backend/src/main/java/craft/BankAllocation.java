package craft;

/**
 * One observed Bank row of a manually edited item and how many of its units are reserved. The set of
 * rows for an item is a complete guard of the layout the player saw, not authority: the backend
 * trusts its own current rows and only keeps the selection when the layout still matches.
 * {@code binding}/{@code boundTo} are raw nullable comparison guards.
 */
public record BankAllocation(int slot, int itemId, int reservedUnits, int observedCount,
                             String binding, String boundTo) {
}

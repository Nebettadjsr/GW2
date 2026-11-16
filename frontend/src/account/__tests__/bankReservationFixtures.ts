import type { BankContents, BankSlot, EffectiveSettings } from '@/api/types'

export const BANK_ITEM = 19697
export interface BankAllocation {
  slot: number
  itemId: number
  reservedUnits: number
  observedCount: number
  binding: string | null
  boundTo: string | null
}
export type GuardedBankSlot = BankSlot & { binding: string | null; boundTo: string | null }
export type BankSnapshot = Omit<BankContents, 'slots'> & { slots: GuardedBankSlot[] }
// The pinned additive wire contract is declared in fixtures until production exports its DTO.
export type BankSettings = EffectiveSettings & { protectedBankAllocations?: BankAllocation[] }
export function fourStackBank(): BankSnapshot {
  return { slotCount: 4, freshness: { status: 'FRESH', completedAt: '2099-01-01T00:00:00Z', refresh: null },
    slots: [0, 1, 2, 3].map(slot => ({ slot, itemId: BANK_ITEM, count: 250,
      itemName: 'Copper Ore', iconUrl: null, rarity: 'Basic', binding: null, boundTo: null })) }
}
/** Explicit expected units, including zero guard rows; this performs no reservation allocation. */
export function allocationRows(units: number[], snapshot = fourStackBank()): BankAllocation[] {
  return snapshot.slots.filter(row => row.itemId === BANK_ITEM && (row.count ?? 0) > 0).map((row, index) => ({
    slot: row.slot, itemId: BANK_ITEM, reservedUnits: units[index]!, observedCount: row.count!,
    binding: row.binding, boundTo: row.boundTo
  }))
}

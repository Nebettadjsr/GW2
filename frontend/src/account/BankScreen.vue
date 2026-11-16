<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { accountApi, type AccountApi } from '@/api/accountApi'
import type { BankContents, BankSlot } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import BankSlotTile from './BankSlotTile.vue'
import { useAccountRead } from './useAccountRead'
import AutomaticRefreshStatus from './AutomaticRefreshStatus.vue'
import { useAutomaticAccountRefresh } from './useAutomaticAccountRefresh'
import MaterialItemActions, { type ItemActionTarget } from './MaterialItemActions.vue'
import ReservationNotes, { type ReservationNote } from './ReservationNotes.vue'
import MaterialSelectionBar from './MaterialSelectionBar.vue'
import { useMaterialProtection, type BankRowGuard } from '@/materials/materialProtectionState'

const SLOTS_PER_TAB = 30
const props = withDefaults(defineProps<{ api?: AccountApi }>(), { api: () => accountApi })
const bank = useAccountRead<BankContents>(() => props.api.loadBank())
const automatic = useAutomaticAccountRefresh({
  operation: 'BANK_SYNC',
  load: bank.load,
  stale: () => ['STALE', 'PENDING'].includes(bank.data.value?.freshness?.status ?? ''),
  timestamps: () => [bank.data.value?.freshness?.completedAt],
  readFailed: () => bank.failure.value !== null
})

onMounted(() => { void bank.load() })
const protection = useMaterialProtection()
const actionTarget = ref<ItemActionTarget | null>(null)
/** Current total across every same-item Bank stack. */
function totalFor(itemId: number): number {
  return (bank.data.value?.slots ?? []).reduce((sum, s) => s.itemId === itemId ? sum + (s.count ?? 0) : sum, 0)
}
function openActions(slot: BankSlot, x: number, y: number): void {
  if (slot.itemId !== null) actionTarget.value = { itemId: slot.itemId, name: slot.itemName ?? null, count: totalFor(slot.itemId), x, y }
}
/** Every current stack of the loaded snapshot with its raw guards. */
const rowGuards = computed<BankRowGuard[]>(() => (bank.data.value?.slots ?? []).flatMap(slot =>
  slot.itemId === null || slot.count === null || slot.count <= 0 ? []
    : [{ slot: slot.slot, itemId: slot.itemId, count: slot.count, binding: slot.binding ?? null, boundTo: slot.boundTo ?? null }]))
/** Per-slot reserved units of the current layout (legacy ALL, matching hints, or ascending redistribution). */
const unitsBySlot = computed(() => {
  const out = new Map<number, number>()
  for (const itemId of new Set(rowGuards.value.map(row => row.itemId))) {
    for (const [slot, units] of protection.bankUnits(itemId, rowGuards.value)) out.set(slot, units)
  }
  return out
})
function stackState(slot: BankSlot): { selected: boolean; mixed: boolean; finite: boolean; reserved: number } {
  const units = unitsBySlot.value.get(slot.slot) ?? 0
  const count = slot.count ?? 0
  const all = slot.itemId !== null && pendingIds.value.has(slot.itemId)
  if (all) return { selected: true, mixed: false, finite: false, reserved: 0 }
  return { selected: units > 0 && units >= count, mixed: units > 0 && units < count, finite: units > 0, reserved: units }
}
// A new snapshot may invalidate manual hints: clean them up with at most one preference write.
watch(() => bank.data.value, data => { if (data?.slots) protection.pruneStaleBankHints(rowGuards.value) })
const pendingIds = computed(() => new Set(protection.pendingFor('bank')))
const selectable = computed(() => protection.state.status === 'ready')
const pageItemIds = computed(() => [...new Set((bank.data.value?.slots ?? [])
  .flatMap(slot => slot.itemId === null || slot.protectionSelectable === false ? [] : [slot.itemId]))])

function isEmptySlot(slot: BankSlot): boolean {
  return slot.itemId === null && slot.count === null
}

/** One note per distinct reserved item in a tab; same-item stacks are not repeated and the target is never clamped. */
function notesFor(slots: BankSlot[]): ReservationNote[] {
  const notes = new Map<number, ReservationNote>()
  for (const slot of slots) {
    if (slot.itemId === null || slot.protectionSelectable === false || pendingIds.value.has(slot.itemId)) continue
    const target = protection.quantityFor('bank', slot.itemId)
    if (target <= 0) continue
    const known = notes.get(slot.itemId)
    if (!known) notes.set(slot.itemId, { itemId: slot.itemId, name: slot.itemName ?? null, target })
    else if (known.name === null && slot.itemName) known.name = slot.itemName
  }
  return [...notes.values()]
}

const hasNoSlots = computed(() => bank.data.value?.slots?.length === 0)

// Group by the original, zero-based slot index, never by item identity or occupancy.
// Slots are already in the backend's canonical order, so no sorting is necessary.
const tabs = computed(() => {
  const slots = bank.data.value?.slots ?? []
  const grouped: { index: number; slots: BankSlot[]; occupied: number }[] = []
  for (const slot of slots) {
    const index = Math.floor(slot.slot / SLOTS_PER_TAB)
    let tab = grouped.find((group) => group.index === index)
    if (!tab) {
      tab = { index, slots: [], occupied: 0 }
      grouped.push(tab)
    }
    tab.slots.push(slot)
    if (!isEmptySlot(slot)) tab.occupied += 1
  }
  return grouped
})

</script>

<template>
  <div class="screen" data-test="bank-screen">
    <!-- The shared heading block, which carries the hooks `App.vue` moves focus to after a
         navigation and groups this page's own actions. -->
    <PageHeader heading="Bank" />
    <AutomaticRefreshStatus :updating="automatic.updating.value" :failed="automatic.failed.value" :has-data="!!bank.data.value?.slots" />

    <MaterialSelectionBar view="bank" :item-ids="pageItemIds" />
    <MaterialItemActions view="bank" :target="actionTarget" @close="actionTarget = null" />
    <div class="stack">
      <p v-if="bank.phase.value === 'loading' && !bank.data.value" class="notice notice--info" role="status" data-test="bank-loading">
        Loading the bank…
      </p>
      <div v-else-if="bank.failure.value !== null && !automatic.failed.value && !automatic.updating.value" class="notice notice--error" role="alert" data-test="bank-error">
        {{ bank.data.value ? 'Could not update the bank. Showing the previous saved snapshot below; it may be out of date.' : 'Could not load the bank. This does not mean your bank is empty.' }}
        <details><summary>Error details</summary>{{ bank.failure.value.code }} — {{ bank.failure.value.message }}</details>
      </div>
      <p v-if="hasNoSlots" class="notice" data-test="bank-no-slots">
        The backend returned a bank with no slots at all
        (<code>slotCount {{ bank.data.value?.slotCount }}</code>).
      </p>
      <template v-else-if="bank.data.value !== null">
        <section v-for="tab in tabs" :key="tab.index" class="bank-tab" :aria-label="`Bank tab ${tab.index + 1}`">
          <div class="bank-tab__header">
            <h2>Bank tab {{ tab.index + 1 }}</h2>
            <span class="meta">{{ tab.occupied }} / {{ tab.slots.length }} slots</span>
          </div>
          <div class="bank-tab__body">
          <div class="bank-tab__scroll">
            <ol class="slots" data-test="bank-slots">
              <BankSlotTile v-for="slot in tab.slots" :key="slot.slot" :slot="slot"
                :selected="selectable && slot.protectionSelectable !== false && slot.itemId !== null ? stackState(slot).selected : undefined"
                :mixed="stackState(slot).mixed" :finite="stackState(slot).finite" :reserved="stackState(slot).reserved"
                @menu="(_id: number, x: number, y: number) => openActions(slot, x, y)"
                @toggle="(id: number) => protection.toggleBankStack(id, slot.slot, rowGuards)" />
            </ol>
          </div>
          <ReservationNotes :notes="notesFor(tab.slots)" source="bank" />
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.screen { width: min(100%, 900px); margin-inline: auto; }
.detail { display: block; margin-top: var(--space-2); }
.bank-tab { margin-block: var(--space-4); }
.bank-tab__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}
.bank-tab__header h2 { margin: 0; font-size: var(--text-base); }
.bank-tab__body { display: flex; flex-wrap: wrap; align-items: flex-start; gap: var(--space-3); }
.bank-tab__scroll { max-width: 100%; overflow-x: auto; }
.slots {
  display: grid;
  grid-template-columns: repeat(10, 60px);
  grid-auto-rows: 60px;
  gap: 4px;
  width: max-content;
  list-style: none;
  margin: 0;
  padding: 0 0 var(--space-2) 0;
}
</style>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { accountApi, type AccountApi } from '@/api/accountApi'
import type { BankContents, BankSlot } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import BankSlotTile from './BankSlotTile.vue'
import { useAccountRead } from './useAccountRead'
import AutomaticRefreshStatus from './AutomaticRefreshStatus.vue'
import { useAutomaticAccountRefresh } from './useAutomaticAccountRefresh'
import MaterialSelectionBar from './MaterialSelectionBar.vue'
import { useMaterialProtection } from '@/materials/materialProtectionState'

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
const pendingIds = computed(() => new Set(protection.pendingFor('bank')))
const selectable = computed(() => protection.state.status === 'ready')
const pageItemIds = computed(() => [...new Set((bank.data.value?.slots ?? [])
  .flatMap(slot => slot.itemId === null || slot.protectionSelectable === false ? [] : [slot.itemId]))])

function isEmptySlot(slot: BankSlot): boolean {
  return slot.itemId === null && slot.count === null
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
          <div class="bank-tab__scroll">
            <ol class="slots" data-test="bank-slots">
              <BankSlotTile v-for="slot in tab.slots" :key="slot.slot" :slot="slot"
                :selected="selectable && slot.protectionSelectable !== false && slot.itemId !== null ? pendingIds.has(slot.itemId) : undefined"
                @toggle="(id: number) => protection.togglePending(id, 'bank')" />
            </ol>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.screen { width: min(100%, 636px); margin-inline: auto; }
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

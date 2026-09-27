<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { accountApi, type AccountApi } from '@/api/accountApi'
import type { BankContents, BankSlot } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import InventoryItem from './InventoryItem.vue'
import { useAccountRead } from './useAccountRead'

/**
 * The Bank screen: the account bank exactly as `GET /api/account/bank` reports it
 * (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * Slots are rendered in the supplied order, empty ones included and in place, because the bank is a
 * grid and dropping them would move every following item into the wrong cell. Nothing is sorted,
 * filtered, grouped or counted into an inventory total here.
 *
 * The `api` prop exists so a test can supply controlled responses; the browser always gets the real
 * backend client.
 */
const props = withDefaults(defineProps<{ api?: AccountApi }>(), { api: () => accountApi })

const bank = useAccountRead<BankContents>(() => props.api.loadBank())

onMounted(() => {
  void bank.load()
})

/**
 * An empty slot is the one the backend represented with both nulls. A null is never read as item
 * id 0 or as an owned count of 0, so a slot carrying one of the two is still an occupied slot with
 * a missing field, and is rendered as such.
 */
function isEmptySlot(slot: BankSlot): boolean {
  return slot.itemId === null && slot.count === null
}

/** Only ever true for a successful read: a failure leaves no data at all. */
const hasNoSlots = computed(() => bank.data.value !== null && bank.data.value.slots.length === 0)

const emptySlotCount = computed(
  () => bank.data.value?.slots.filter((slot) => isEmptySlot(slot)).length ?? 0
)

function onReload(): void {
  void bank.load()
}
</script>

<template>
  <div class="screen" data-test="bank-screen">
    <PageHeader
      heading="Bank"
      intro="The account bank as the last synchronization stored it. Nothing on this page changes the account or starts a synchronization."
    >
      <template #actions>
        <button
          type="button"
          class="button--primary"
          data-test="bank-reload"
          :disabled="bank.phase.value === 'loading'"
          @click="onReload"
        >
          Reload bank
        </button>
      </template>
    </PageHeader>

    <div class="stack">
      <p class="meta prose">
        Items are shown by their id: the backend supplies no item name for the bank, so none is
        invented here. An item's icon is the image this application serves for it, and a neutral
        placeholder stands in wherever there is none. Empty slots keep their position in the bank
        grid.
      </p>

      <p
        v-if="bank.phase.value === 'loading'"
        class="notice notice--info"
        role="status"
        data-test="bank-loading"
      >
        Loading the bank…
      </p>

      <p v-else-if="bank.failure.value !== null" class="notice notice--error" data-test="bank-error">
        The bank could not be read, so nothing is shown for it — this is a failure, not an empty
        bank.
        <span class="meta detail">
          Backend answer: {{ bank.failure.value.code }} — {{ bank.failure.value.message }}
        </span>
        <span class="notice__actions">
          <button type="button" data-test="bank-retry" @click="onReload">Try again</button>
        </span>
      </p>

      <p v-else-if="hasNoSlots" class="notice" data-test="bank-no-slots">
        The backend returned a bank with no slots at all
        (<code>slotCount {{ bank.data.value?.slotCount }}</code>). This is a successful read of an
        empty result, not a failed one and not a bank whose slots are empty.
      </p>

      <template v-else-if="bank.data.value !== null">
        <p class="meta" data-test="bank-summary">
          {{ bank.data.value.slotCount }} slots supplied · {{ emptySlotCount }} of the
          {{ bank.data.value.slots.length }} rendered slots are empty
        </p>

        <ol class="slots" data-test="bank-slots">
          <li
            v-for="slot in bank.data.value.slots"
            :key="slot.slot"
            class="slot"
            data-test="bank-slot"
            :data-slot="slot.slot"
            :class="{ 'slot--empty': isEmptySlot(slot) }"
          >
            <span class="slot-number" data-test="bank-slot-number">#{{ slot.slot }}</span>

            <span v-if="isEmptySlot(slot)" class="empty" data-test="bank-empty-slot">Empty</span>

            <InventoryItem
              v-else
              :item-id="slot.itemId"
              :count="slot.count"
              :rarity="slot.rarity"
              :icon-url="slot.iconUrl"
            />
          </li>
        </ol>
      </template>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: block;
  margin-top: var(--space-2);
}

/*
 * A fluid grid rather than a fixed number of columns: the same slot order fills one column on a
 * phone and many on a wide screen, without any slot changing its position in the sequence.
 */
.slots {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 15rem), 1fr));
  gap: var(--space-2);
  list-style: none;
  margin: 0;
  padding: 0;
}

.slot {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
}

/* Empty slots are marked structurally (dashed, unfilled) rather than faded out of readability. */
.slot--empty {
  border-style: dashed;
  background: transparent;
}

.slot-number {
  min-width: 3.5rem;
  color: var(--color-muted);
  font-variant-numeric: tabular-nums;
}

.empty {
  color: var(--color-muted);
}
</style>

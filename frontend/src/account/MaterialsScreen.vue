<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { accountApi, type AccountApi } from '@/api/accountApi'
import type { MaterialStorage } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import InventoryItem from './InventoryItem.vue'
import { useAccountRead } from './useAccountRead'

/**
 * The Materials screen: account material storage exactly as `GET /api/account/materials` grouped it
 * (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * The categories, their order, their labels — the backend's `"Category <id>"` fallback included —
 * and the stack order within each are the backend's and are rendered unchanged. Nothing is
 * regrouped, deduplicated, sorted, aggregated or filtered here, and no category map or inclusion
 * rule exists in this screen.
 *
 * The `api` prop exists so a test can supply controlled responses; the browser always gets the real
 * backend client.
 */
const props = withDefaults(defineProps<{ api?: AccountApi }>(), { api: () => accountApi })

const materials = useAccountRead<MaterialStorage>(() => props.api.loadMaterials())

onMounted(() => {
  void materials.load()
})

/** Only ever true for a successful read: a failure leaves no data at all. */
const hasNoCategories = computed(
  () => materials.data.value !== null && materials.data.value.categories.length === 0
)

function onReload(): void {
  void materials.load()
}
</script>

<template>
  <div class="screen" data-test="materials-screen">
    <PageHeader
      heading="Materials"
      intro="Account material storage as the last synchronization stored it, in the groups the backend supplies. Nothing on this page changes the account or starts a synchronization."
    >
      <template #actions>
        <button
          type="button"
          class="button--primary"
          data-test="materials-reload"
          :disabled="materials.phase.value === 'loading'"
          @click="onReload"
        >
          Reload materials
        </button>
      </template>
    </PageHeader>

    <div class="stack">
      <p class="meta prose">
        The grouping, the order and the group names are the backend's and are shown unchanged. Items
        are shown by their id: no item name and no icon image is available for material storage, so
        none is invented here.
      </p>

      <p
        v-if="materials.phase.value === 'loading'"
        class="notice notice--info"
        role="status"
        data-test="materials-loading"
      >
        Loading material storage…
      </p>

      <p
        v-else-if="materials.failure.value !== null"
        class="notice notice--error"
        data-test="materials-error"
      >
        Material storage could not be read, so nothing is shown for it — this is a failure, not empty
        material storage.
        <span class="meta detail">
          Backend answer: {{ materials.failure.value.code }} — {{ materials.failure.value.message }}
        </span>
        <span class="notice__actions">
          <button type="button" data-test="materials-retry" @click="onReload">Try again</button>
        </span>
      </p>

      <p v-else-if="hasNoCategories" class="notice" data-test="materials-empty">
        The backend returned no material categories
        (<code>categoryCount {{ materials.data.value?.categoryCount }}</code>). This is a successful
        read of an empty result, not a failed one.
      </p>

      <template v-else-if="materials.data.value !== null">
        <p class="meta" data-test="materials-summary">
          {{ materials.data.value.categoryCount }} categories supplied, in the backend's order
        </p>

        <section
          v-for="(category, categoryIndex) in materials.data.value.categories"
          :key="`${categoryIndex}-${category.name}`"
          class="category"
          data-test="material-category"
        >
          <div class="category__head">
            <h2 data-test="material-category-name">{{ category.name }}</h2>
            <p class="meta" data-test="material-stack-count">
              {{ category.materials.length }} stacks
            </p>
          </div>

          <ol class="stacks">
            <li
              v-for="(stack, stackIndex) in category.materials"
              :key="`${stackIndex}-${stack.itemId}`"
              class="stack-entry"
              data-test="material-stack"
            >
              <InventoryItem
                :item-id="stack.itemId"
                :count="stack.count"
                :rarity="stack.rarity"
                :icon-url="stack.iconUrl"
              />
              <span class="category-id" data-test="material-stack-category">
                category id {{ stack.category }}
              </span>
            </li>
          </ol>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: block;
  margin-top: var(--space-2);
}

.category {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.category__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-3);
}

.category h2 {
  font-size: var(--text-lg);
}

/* Fluid, so one supplied stack order reflows from one column to many without changing sequence. */
.stacks {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 17rem), 1fr));
  gap: var(--space-2);
  list-style: none;
  margin: 0;
  padding: 0;
}

.stack-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
}

/* Supplied per stack, and shown per stack: which id produced a label is not inferred from siblings. */
.category-id {
  color: var(--color-muted);
  font-size: var(--text-sm);
  white-space: nowrap;
}
</style>

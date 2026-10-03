<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { accountApi, type AccountApi } from '@/api/accountApi'
import type { MaterialStorage } from '@/api/types'
import InventoryTile from './InventoryTile.vue'
import { useAccountRead } from './useAccountRead'

const props = withDefaults(defineProps<{ api?: AccountApi }>(), { api: () => accountApi })
const materials = useAccountRead<MaterialStorage>(() => props.api.loadMaterials())
onMounted(() => { void materials.load() })
const hasNoCategories = computed(() => materials.data.value !== null && materials.data.value.categories.length === 0)
function onReload(): void { void materials.load() }
</script>

<template>
  <div class="screen" data-test="materials-screen">
    <header class="materials-page-header">
      <h1>Materials</h1>
      <button type="button" class="button--primary" data-test="materials-reload"
        :disabled="materials.phase.value === 'loading'" @click="onReload">Reload materials</button>
    </header>
    <div class="stack">
      <p v-if="materials.phase.value === 'loading'" class="notice notice--info" role="status" data-test="materials-loading">Loading material storage…</p>
      <p v-else-if="materials.failure.value !== null" class="notice notice--error" data-test="materials-error">
        Material storage could not be read, so nothing is shown for it — this is a failure, not empty material storage.
        <span class="meta detail">Backend answer: {{ materials.failure.value.code }} — {{ materials.failure.value.message }}</span>
        <span class="notice__actions"><button type="button" data-test="materials-retry" @click="onReload">Try again</button></span>
      </p>
      <p v-else-if="hasNoCategories" class="notice" data-test="materials-empty">No material categories are available.</p>
      <template v-else-if="materials.data.value !== null">
        <section v-for="category in materials.data.value.categories" :key="category.category" class="material-category" data-test="material-category">
          <h2 data-test="material-category-name">{{ category.name }}</h2>
          <div class="material-scroll">
            <ol class="material-grid" data-test="material-grid">
              <li v-for="material in category.materials" :key="material.position" data-test="material-stack">
                <InventoryTile :item-id="material.itemId" :count="material.count" :icon-url="material.iconUrl"
                  :show-one="true" :title="`Item #${material.itemId}`" />
              </li>
            </ol>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.screen { width: min(100%, 636px); margin-inline: auto; }
.materials-page-header { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-4); }
.materials-page-header h1 { margin: 0; }
.detail { display: block; margin-top: var(--space-2); }
.material-category { margin-block: var(--space-4); }
.material-category h2 { margin: 0 0 var(--space-2); font-size: var(--text-base); }
.material-scroll { max-width: 100%; overflow-x: auto; }
.material-grid { display: grid; grid-template-columns: repeat(10, 60px); grid-auto-rows: 60px; gap: 4px; width: max-content; list-style: none; margin: 0; padding: 0 0 var(--space-2); }
</style>

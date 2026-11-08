<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { accountApi, type AccountApi } from '@/api/accountApi'
import type { MaterialCategory, MaterialStorage } from '@/api/types'
import PageHeader from '@/shell/PageHeader.vue'
import InventoryTile from './InventoryTile.vue'
import { useAccountRead } from './useAccountRead'
import AutomaticRefreshStatus from './AutomaticRefreshStatus.vue'
import { useAutomaticAccountRefresh } from './useAutomaticAccountRefresh'
import MaterialSelectionBar from './MaterialSelectionBar.vue'
import { useMaterialProtection } from '@/materials/materialProtectionState'
import { MATERIAL_PRESETS, type MaterialPreset } from '@/materials/materialPresets'

const props = withDefaults(defineProps<{ api?: AccountApi }>(), { api: () => accountApi })
const materials = useAccountRead<MaterialStorage>(() => props.api.loadMaterials())
const automatic = useAutomaticAccountRefresh({
  operation: 'MATERIALS_SYNC',
  load: materials.load,
  stale: () => ['STALE', 'PENDING'].includes(materials.data.value?.freshness?.status ?? ''),
  timestamps: () => [materials.data.value?.freshness?.completedAt],
  readFailed: () => materials.failure.value !== null
})
onMounted(() => { void materials.load() })
const protection = useMaterialProtection()
const pendingIds = computed(() => new Set(protection.pendingFor('materials')))
const selectable = computed(() => protection.state.status === 'ready')
const hasNoCategories = computed(() => materials.data.value?.categories?.length === 0)
const pageItemIds = computed(() => (materials.data.value?.categories ?? [])
  .flatMap(category => category.materials.map(material => material.itemId)))
function categorySelected(category: MaterialCategory): boolean {
  return category.materials.length > 0 && category.materials.every(material => pendingIds.value.has(material.itemId))
}
function toggleCategory(category: MaterialCategory): void {
  protection.togglePendingGroup(category.materials.map(material => material.itemId), 'materials')
}
function presetsFor(category: MaterialCategory): readonly MaterialPreset[] {
  return MATERIAL_PRESETS.filter(preset => preset.categoryId === category.category)
}
function presetSelected(preset: MaterialPreset): boolean {
  return preset.itemIds.every(id => pendingIds.value.has(id))
}
</script>

<template>
  <div class="screen" data-test="materials-screen">
    <!-- The shared heading block, which carries the hooks `App.vue` moves focus to after a
         navigation and groups this page's own actions. -->
    <PageHeader heading="Materials" />
    <AutomaticRefreshStatus :updating="automatic.updating.value" :failed="automatic.failed.value" :has-data="!!materials.data.value?.categories" />
    <MaterialSelectionBar view="materials" :item-ids="pageItemIds" />
    <div class="stack">
      <p v-if="materials.phase.value === 'loading' && !materials.data.value" class="notice notice--info" role="status" data-test="materials-loading">Loading material storage…</p>
      <div v-else-if="materials.failure.value !== null && !automatic.failed.value && !automatic.updating.value" class="notice notice--error" role="alert" data-test="materials-error">
        {{ materials.data.value ? 'Could not update material storage. Showing the previous saved snapshot below; it may be out of date.' : 'Could not load material storage. This does not mean your material storage is empty.' }}
        <details><summary>Error details</summary>{{ materials.failure.value.code }} — {{ materials.failure.value.message }}</details>
      </div>
      <p v-if="hasNoCategories" class="notice" data-test="materials-empty">No material categories are available.</p>
      <template v-else-if="materials.data.value !== null">
        <section v-for="category in materials.data.value.categories" :key="category.category" class="material-category" data-test="material-category">
          <h2 data-test="material-category-name">{{ category.name }}</h2>
          <div v-if="selectable" class="material-category__actions">
            <span v-if="presetsFor(category).length" class="material-category__presets" role="group"
              :aria-label="`${category.name} presets`" data-test="selection-presets">
              <button v-for="preset in presetsFor(category)" :key="preset.id" type="button" class="button--secondary"
                :aria-pressed="presetSelected(preset)" :title="preset.tooltip" :data-test="`preset-${preset.id}`"
                @click="protection.togglePendingGroup(preset.itemIds, 'materials')">{{ preset.label }}</button>
            </span>
            <button type="button" class="button--secondary material-category__toggle" data-test="category-toggle"
              :disabled="category.materials.length === 0"
              :aria-label="`${categorySelected(category) ? 'Deselect' : 'Select'} all ${category.name}`"
              @click="toggleCategory(category)">{{ categorySelected(category) ? 'Deselect all' : 'Select all' }}</button>
          </div>
          <div class="material-scroll">
            <ol class="material-grid" data-test="material-grid">
              <li v-for="material in category.materials" :key="material.position" data-test="material-stack">
                <InventoryTile :item-id="material.itemId" :count="material.count" :icon-url="material.iconUrl"
                  :show-one="true" :title="`Item #${material.itemId}`"
                  :selected="selectable ? pendingIds.has(material.itemId) : undefined"
                  @toggle="(id: number) => protection.togglePending(id, 'materials')" />
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
.detail { display: block; margin-top: var(--space-2); }
.material-category { margin-block: var(--space-4); }
.material-category h2 { margin: 0 0 var(--space-2); font-size: var(--text-base); }
.material-category__actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--space-2); margin-bottom: var(--space-2); }
.material-category__presets { display: flex; flex-wrap: wrap; gap: var(--space-2); min-width: 0; max-width: 100%; }
.material-category__actions button { padding: 4px 10px; font-size: var(--text-sm); }
.material-category__toggle { margin-inline-start: auto; }
.material-category__presets button[aria-pressed='true'] { border-color: var(--color-accent); box-shadow: inset 0 0 0 1px var(--color-accent); }
.material-scroll { max-width: 100%; overflow-x: auto; }
.material-grid { display: grid; grid-template-columns: repeat(10, 60px); grid-auto-rows: 60px; gap: 4px; width: max-content; list-style: none; margin: 0; padding: 0 0 var(--space-2); }
</style>

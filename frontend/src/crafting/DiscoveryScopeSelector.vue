<script setup lang="ts">
import type { DiscoveryScopeOption } from './discoveryScopeOptions'

/**
 * Crafting Discovery's two calculation-scope controls (`DOMAIN_SPEC.md` 2.2.2,
 * `CURRENT_ARCHITECTURE.md` 5.2/5.6).
 *
 * **Character and discipline** is the scope: individual entries only, each carrying the rating the
 * backend reported for it. There is no All entry, because Discovery has no All reading.
 *
 * **Inventory character** is the separate, independent `inventoryCharacterName` input, and its label
 * says what it is for: it decides whose owned materials the calculation may consume, not which
 * recipes are listed — recipe knowledge is account-wide (`DOMAIN_SPEC.md` 34). "No character" is a
 * real choice that sends nothing, leaving the backend's own unfiltered owned-material pool in place.
 *
 * Both selects stay usable while a calculation is in flight, for the reason `ScopeSelector` gives:
 * locking them would strand the user and make the screen's superseded-answer handling unreachable.
 */
defineProps<{
  options: readonly DiscoveryScopeOption[]
  selectedId: string | null
  inventoryCharacterNames: readonly string[]
  selectedInventoryCharacter: string | null
}>()

const emit = defineEmits<{
  select: [scopeOptionId: string]
  selectInventoryCharacter: [characterName: string | null]
}>()

/** The `<option>` value standing for "no character"; empty because a character name never is. */
const NO_CHARACTER = ''

function onScopeChange(event: Event): void {
  const target = event.target
  if (target instanceof HTMLSelectElement) emit('select', target.value)
}

function onInventoryChange(event: Event): void {
  const target = event.target
  if (!(target instanceof HTMLSelectElement)) return
  emit('selectInventoryCharacter', target.value === NO_CHARACTER ? null : target.value)
}
</script>

<template>
  <div class="discovery-scope">
    <label class="scope">
      <span>Character and discipline</span>
      <select
        data-test="discovery-scope-selector"
        :value="selectedId ?? ''"
        :disabled="options.length === 0"
        @change="onScopeChange"
      >
        <option v-for="option in options" :key="option.id" :value="option.id">
          {{ option.label }}
        </option>
      </select>
    </label>

    <label class="scope">
      <span>Inventory character</span>
      <select
        data-test="discovery-inventory-selector"
        aria-describedby="discovery-inventory-help"
        :value="selectedInventoryCharacter ?? NO_CHARACTER"
        @change="onInventoryChange"
      >
        <option :value="NO_CHARACTER">No character — all owned materials</option>
        <option v-for="name in inventoryCharacterNames" :key="name" :value="name">{{ name }}</option>
      </select>
      <span id="discovery-inventory-help" class="hint" data-test="discovery-inventory-help">
        Whose owned materials the calculation may use. Which recipes are listed does not depend on it:
        recipe knowledge is account-wide.
      </span>
    </label>
  </div>
</template>

<style scoped>
.discovery-scope {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3) var(--space-5);
}

.scope {
  /* Each label owns its column so the help sentence sits under its own control. */
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

select {
  /* Wide enough for a character-and-discipline entry, but it shrinks with the available width. */
  width: 18rem;
  max-width: 100%;
}

.hint {
  max-width: 18rem;
  color: var(--color-muted);
  font-size: var(--text-sm);
}
</style>

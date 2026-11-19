<script setup lang="ts">
import { ref, useId } from 'vue'
import type { NoteEstimate } from '@/api/types'
import { formatCopper } from './formatCopper'
import { wikiUrl } from './recipeLabel'

defineProps<{ estimate: NoteEstimate }>()

const dialogId = useId()
const headingId = `${dialogId}-heading`
const trigger = ref<HTMLButtonElement | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)

function open(): void {
  dialog.value?.showModal()
}

function close(): void {
  dialog.value?.close()
}

function restoreFocus(): void {
  trigger.value?.focus()
}
</script>

<template>
  <span class="note-estimate">
    <button
      ref="trigger"
      type="button"
      class="note-estimate__trigger"
      aria-haspopup="dialog"
      :aria-controls="dialogId"
      :disabled="estimate.variants.length === 0"
      :title="estimate.variants.length === 0 ? 'Recipe comparison is loading' : undefined"
      aria-label="Estimated Research Note value: show recipe comparison"
      data-test="note-estimate-trigger"
      @click="open"
    >
      Estimated
    </button>

    <dialog
      :id="dialogId"
      ref="dialog"
      class="note-estimate__dialog"
      :aria-labelledby="headingId"
      data-test="note-estimate-dialog"
      @close="restoreFocus"
    >
      <h2 :id="headingId">Research Note estimate</h2>
      <p>
        Research Notes paid from your existing wallet are valued at the cheaper fully priced
        Peer Reviewed Research recipe (150 Notes per item), using current prices. Owned
        materials are not treated as free.
      </p>

      <section
        v-for="variant in estimate.variants"
        :key="variant.recipeId"
        class="note-estimate__variant"
        :data-test="`note-estimate-variant-${variant.recipeId}`"
      >
        <h3>
          <a
            v-if="wikiUrl(variant.outputName)"
            :href="wikiUrl(variant.outputName) ?? undefined"
            target="_blank"
            rel="noopener noreferrer"
          >{{ variant.outputName }}</a>
          <template v-else>Recipe {{ variant.recipeId }}</template>
          <span class="meta"> (recipe {{ variant.recipeId }})</span>
          <strong
            v-if="variant.basis"
            class="note-estimate__basis"
            data-test="note-estimate-basis"
          > · Estimate basis (cheaper)</strong>
        </h3>
        <ul>
          <li v-for="line in variant.ingredients" :key="line.itemId">
            {{ line.count }} ×
            <a
              v-if="wikiUrl(line.name)"
              :href="wikiUrl(line.name) ?? undefined"
              target="_blank"
              rel="noopener noreferrer"
            >{{ line.name }}</a>
            <template v-else>item {{ line.itemId }}</template>:
            <span v-if="line.unitPriceCopper !== null">
              {{ formatCopper(line.unitPriceCopper) }} each, {{ formatCopper(line.totalCopper) }}
            </span>
            <span v-else data-test="note-estimate-missing">no price available</span>
          </li>
        </ul>
        <p v-if="variant.batchCostCopper !== null" data-test="note-estimate-cost">
          Cost per {{ variant.notesPerBatch }}-Note batch: {{ formatCopper(variant.batchCostCopper) }};
          per Note: about {{ formatCopper(variant.perNoteCopperCeil) }}
        </p>
        <p v-else data-test="note-estimate-unavailable">
          Cannot be completely priced, so it is not used for the estimate.
        </p>
      </section>

      <button type="button" data-test="note-estimate-close" @click="close">Close</button>
    </dialog>
  </span>
</template>

<style scoped>
.note-estimate__trigger {
  min-height: 0;
  margin-left: var(--space-2);
  padding: 0 var(--space-1);
  font-size: var(--text-sm);
  background: none;
  text-decoration: underline dotted;
}

.note-estimate__dialog {
  width: min(36rem, calc(100% - 2 * var(--space-4)));
  max-height: calc(100% - 2 * var(--space-4));
  overflow-y: auto;
  padding: var(--space-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
  color: var(--color-text);
  text-align: left;
  white-space: normal;
}

.note-estimate__dialog::backdrop {
  background: rgb(0 0 0 / 65%);
}

.note-estimate__variant {
  margin: var(--space-3) 0;
}
</style>

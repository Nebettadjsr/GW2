<script setup lang="ts">
import { ref, useId } from 'vue'
import type { NoteEstimate } from '@/api/types'
import ItemIcon from '@/items/ItemIcon.vue'
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
      data-test="note-estimate-trigger"
      @click="open"
    >
      Estimated value
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
      <p class="note-estimate__intro">
        Wallet Notes are valued at the cheaper fully priced recipe, using current prices.
      </p>

      <div class="note-estimate__grid">
        <section
          v-for="variant in estimate.variants"
          :key="variant.recipeId"
          class="note-estimate__card"
          :data-test="`note-estimate-variant-${variant.recipeId}`"
        >
          <header class="note-estimate__card-head">
            <h3>
              <a
                v-if="wikiUrl(variant.outputName)"
                :href="wikiUrl(variant.outputName) ?? undefined"
                target="_blank"
                rel="noopener noreferrer"
              >{{ variant.outputName }}</a>
              <template v-else>Recipe {{ variant.recipeId }}</template>
            </h3>
            <strong
              v-if="variant.basis"
              class="note-estimate__badge"
              data-test="note-estimate-basis"
            >Cheaper</strong>
            <span class="meta note-estimate__id">Recipe {{ variant.recipeId }}</span>
          </header>

          <table class="note-estimate__table">
            <tbody>
              <tr v-for="line in variant.ingredients" :key="line.itemId" data-test="note-estimate-line">
                <td class="numeric">{{ line.count }}</td>
                <td class="note-estimate__icon">
                  <ItemIcon :icon-url="line.iconUrl ?? null" :item-id="line.itemId" :size="24" />
                </td>
                <td>
                  <a
                    v-if="wikiUrl(line.name)"
                    :href="wikiUrl(line.name) ?? undefined"
                    target="_blank"
                    rel="noopener noreferrer"
                  >{{ line.name }}</a>
                  <template v-else>Item {{ line.itemId }}</template>
                </td>
                <td class="numeric">
                  <span v-if="line.totalCopper !== null">{{ formatCopper(line.totalCopper) }}</span>
                  <span v-else class="meta" data-test="note-estimate-missing">No price</span>
                </td>
              </tr>
            </tbody>
          </table>

          <dl class="note-estimate__summary">
            <dt>Total ({{ variant.notesPerBatch }} Research Notes)</dt>
            <dd class="numeric" data-test="note-estimate-cost">
              <template v-if="variant.batchCostCopper !== null">{{ formatCopper(variant.batchCostCopper) }}</template>
              <span v-else class="meta" data-test="note-estimate-unavailable">Unavailable</span>
            </dd>
            <dt>100 Research Notes</dt>
            <dd class="numeric" data-test="note-estimate-per100">
              <template v-if="variant.per100NotesCopper != null">{{ formatCopper(variant.per100NotesCopper) }}</template>
              <span v-else class="meta">Unavailable</span>
            </dd>
          </dl>
        </section>
      </div>

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
  width: min(60rem, calc(100% - 2 * var(--space-4)));
  font-size: 1rem;
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

.note-estimate__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  margin: var(--space-3) 0;
}

.note-estimate__card {
  min-width: 0;
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
}

.note-estimate__card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-2);
}

.note-estimate__card-head h3 {
  margin: 0;
}

.note-estimate__id {
  margin-left: auto;
  font-size: var(--text-sm);
}

.note-estimate__badge {
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  font-size: var(--text-sm);
}

.note-estimate__table {
  width: 100%;
  margin: var(--space-3) 0;
  border-collapse: collapse;
}

.note-estimate__table td {
  padding: var(--space-1) var(--space-2);
  vertical-align: middle;
}

.note-estimate__table td.numeric:last-child {
  white-space: nowrap;
}

.note-estimate__icon {
  width: 1%;
  line-height: 0;
}

.note-estimate__summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-1) var(--space-3);
  margin: 0;
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-border);
}

.note-estimate__summary dt,
.note-estimate__summary dd {
  margin: 0;
}
</style>

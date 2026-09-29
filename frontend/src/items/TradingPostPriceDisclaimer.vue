<script setup lang="ts">
import { ref, useId } from 'vue'

const dialogId = useId()
const headingId = `${dialogId}-heading`
const messageId = `${dialogId}-message`
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
  <div class="tp-price-disclaimer">
    <button
      ref="trigger"
      type="button"
      class="tp-price-disclaimer__trigger"
      aria-label="Trading Post price warning"
      aria-haspopup="dialog"
      :aria-controls="dialogId"
      data-test="tp-price-disclaimer-trigger"
      @click="open"
    >
      <span aria-hidden="true">ⓘ</span> TP price warning
    </button>

    <dialog
      :id="dialogId"
      ref="dialog"
      class="tp-price-disclaimer__dialog"
      :aria-labelledby="headingId"
      :aria-describedby="messageId"
      data-test="tp-price-disclaimer-dialog"
      @close="restoreFocus"
    >
      <h2 :id="headingId">Trading Post price warning</h2>

      <p :id="messageId" class="tp-price-disclaimer__warning">
        CHECK IN-GAME PRICE &amp; QUANTITY
      </p>

      <p>
        The GW2 API does not provide Trading Post order-book depth. This tool therefore only knows
        the current best unit price — not how many items are actually available at that price.
      </p>

      <p>
        <strong>Before buying or selling large quantities, check the Trading Post in-game.</strong>
      </p>
      <button type="button" data-test="tp-price-disclaimer-close" @click="close">Close</button>
    </dialog>
  </div>
</template>

<style scoped>
.tp-price-disclaimer {
  display: inline-flex;
  align-self: flex-start;
}

.tp-price-disclaimer__trigger {
  min-height: 0;
  padding: var(--space-1) var(--space-2);
  color: var(--color-danger, #ff6b6b);
  border-color: var(--color-danger, #ff6b6b);
  font-size: var(--text-sm);
}

.tp-price-disclaimer__dialog {
  width: min(32rem, calc(100% - 2 * var(--space-4)));
  max-height: calc(100% - 2 * var(--space-4));
  overflow-y: auto;
  padding: var(--space-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
  color: var(--color-text);
}

.tp-price-disclaimer__dialog::backdrop {
  background: rgb(0 0 0 / 65%);
}

.tp-price-disclaimer__warning {
  margin: var(--space-4) 0 var(--space-3);
  color: var(--color-danger, #ff6b6b);
  font-size: 1.35rem;
  font-weight: 700;
  line-height: 1.2;
}

.tp-price-disclaimer__dialog p {
  margin: var(--space-3) 0;
  max-width: var(--prose-max);
}

.tp-price-disclaimer__dialog p:first-of-type {
  margin-top: var(--space-4);
}

.tp-price-disclaimer__dialog p:last-of-type {
  margin-bottom: var(--space-4);
}
</style>
